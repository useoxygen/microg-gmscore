// SPDX-FileCopyrightText: 2026 Cyclon
// SPDX-License-Identifier: Apache-2.0
// Native PAKE TLS counterpart built against the independently pinned public BoringSSL.
#include <openssl/ssl.h>
#include <openssl/err.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <netdb.h>
#include <netinet/in.h>
#include <unistd.h>
#include <poll.h>
#include <signal.h>
#include <atomic>
#include <fstream>
#include <future>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <memory>

struct Config { std::string session = "abc234", password = "def567", context = "os-migration"; };
void require(bool ok) { if (!ok) throw std::runtime_error("PAKE peer operation failed"); }
using Context = std::unique_ptr<SSL_CTX, decltype(&SSL_CTX_free)>;
using Tls = std::unique_ptr<SSL, decltype(&SSL_free)>;
Context context(const Config& cfg, bool server, bool certificate_only = false) {
  Context ctx(SSL_CTX_new(TLS_method()), SSL_CTX_free); require(bool(ctx));
  require(SSL_CTX_set_min_proto_version(ctx.get(), TLS1_3_VERSION));
  require(SSL_CTX_set_max_proto_version(ctx.get(), TLS1_3_VERSION));
  if (certificate_only) return ctx;
  uint8_t w0[32], w1[32], record[65];
  const auto* session = reinterpret_cast<const uint8_t*>(cfg.session.data());
  const auto* password = reinterpret_cast<const uint8_t*>(cfg.password.data());
  const auto* tag = reinterpret_cast<const uint8_t*>(cfg.context.data());
  require(SSL_spake2plusv1_register(w0,w1,record,password,cfg.password.size(),nullptr,0,session,cfg.session.size()));
  SSL_CREDENTIAL* credential = server
      ? SSL_CREDENTIAL_new_spake2plusv1_server(tag,cfg.context.size(),nullptr,0,session,cfg.session.size(),24,w0,sizeof(w0),record,sizeof(record))
      : SSL_CREDENTIAL_new_spake2plusv1_client(tag,cfg.context.size(),nullptr,0,session,cfg.session.size(),24,w0,sizeof(w0),w1,sizeof(w1));
  require(credential != nullptr);
  int added = SSL_CTX_add1_credential(ctx.get(),credential); SSL_CREDENTIAL_free(credential); require(added);
  return ctx;
}
void timeout(int fd) { timeval limit{10,0}; setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&limit,sizeof(limit)); setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&limit,sizeof(limit)); }
Tls tls(SSL_CTX* ctx, int fd, bool server) {
  timeout(fd); Tls ssl(SSL_new(ctx),SSL_free); require(bool(ssl)); require(SSL_set_fd(ssl.get(),fd));
  require((server ? SSL_accept(ssl.get()) : SSL_connect(ssl.get())) == 1); return ssl;
}
void self_test() {
  Config reference;
  for (int test=0; test<5; ++test) {
    Config client = reference;
    if (test==1) client.password = "xyz234";
    if (test==2) client.session = "xyz234";
    if (test==3) client.context = "wrong-context";
    auto sc = context(reference,true); auto cc = context(client,false,test==4);
    int fds[2]; require(socketpair(AF_UNIX,SOCK_STREAM,0,fds)==0);
    auto accepted = std::async(std::launch::async,[&] {
      bool ok=false;
      try { auto peer=tls(sc.get(),fds[0],true); char b; require(SSL_read(peer.get(),&b,1)==1 && b=='a'); require(SSL_write(peer.get(),"b",1)==1); ok=true; }
      catch (...) {} close(fds[0]); return ok;
    });
    bool ok=false;
    try { auto peer=tls(cc.get(),fds[1],false); require(SSL_write(peer.get(),"a",1)==1); char b; require(SSL_read(peer.get(),&b,1)==1 && b=='b'); ok=true; }
    catch (...) {} close(fds[1]);
    const bool server_ok=accepted.get(); require(test==0 ? (ok && server_ok) : (!ok && !server_ok));
    std::cout << "PASS " << (test==0 ? "PAKE TLS1.3 exchange" : test==1 ? "wrong password rejected" : test==2 ? "wrong server identity rejected" : test==3 ? "wrong context rejected" : "ordinary TLS rejected") << '\n';
  }
}
int connect_to(const std::string& host, const std::string& port) {
  addrinfo hints{}; hints.ai_socktype=SOCK_STREAM; addrinfo* addresses=nullptr;
  require(getaddrinfo(host.c_str(),port.c_str(),&hints,&addresses)==0); int fd=-1;
  for(auto* a=addresses;a;a=a->ai_next) { fd=socket(a->ai_family,a->ai_socktype,a->ai_protocol); if(fd<0)continue; timeout(fd); if(connect(fd,a->ai_addr,a->ai_addrlen)==0)break; close(fd); fd=-1; }
  freeaddrinfo(addresses); require(fd>=0); return fd;
}
void send_all(int fd,const char* bytes,int count) { while(count>0) { int n=send(fd,bytes,count,0); require(n>0); bytes+=n; count-=n; } }
void tls_all(SSL* ssl,const char* bytes,int count) { while(count>0) { int n=SSL_write(ssl,bytes,count); require(n>0); bytes+=n; count-=n; } }
void proxy(SSL* ssl,int plain) {
  const int encrypted=SSL_get_fd(ssl); char buffer[65536];
  while(true) {
    pollfd fds[2]={{encrypted,POLLIN,0},{plain,POLLIN,0}};
    if(!SSL_pending(ssl)) require(poll(fds,2,35000)>0);
    if(SSL_pending(ssl) || (fds[0].revents&(POLLIN|POLLHUP))) {
      int count=SSL_read(ssl,buffer,sizeof(buffer)); if(count<=0) break; send_all(plain,buffer,count);
    }
    if(fds[1].revents&(POLLIN|POLLHUP)) { int count=recv(plain,buffer,sizeof(buffer),0); if(count<=0)break; tls_all(ssl,buffer,count); }
  }
}
int main(int argc,char** argv) {
  signal(SIGPIPE,SIG_IGN);
  try {
    if(argc==2 && std::string(argv[1])=="--self-test") { self_test(); return 0; }
    require(argc==7);
    const bool server=std::string(argv[1])=="--server"; require(server || std::string(argv[1])=="--client");
    struct stat st{}; require(stat(argv[2],&st)==0 && (st.st_mode&077)==0);
    std::ifstream config(argv[2]); Config cfg; require(bool(std::getline(config,cfg.session)) && bool(std::getline(config,cfg.password)));
    require(cfg.session.size()==6 && cfg.password.size()==6);
    auto ctx=context(cfg,server);
    addrinfo hints{}; hints.ai_socktype=SOCK_STREAM; hints.ai_flags=AI_NUMERICHOST; addrinfo* address=nullptr;
    require(getaddrinfo(argv[3],argv[4],&hints,&address)==0);
    int listener=socket(address->ai_family,SOCK_STREAM,0); require(listener>=0);
    require(bind(listener,address->ai_addr,address->ai_addrlen)==0); freeaddrinfo(address); require(listen(listener,8)==0);
    std::cout << "READY PAKE " << (server?"server":"client proxy") << " (credentials private; 60 second run)" << std::endl;
    std::atomic<int> active{0}; auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(60);
    std::vector<std::thread> workers;
    while(std::chrono::steady_clock::now()<deadline) {
      pollfd ready{listener,POLLIN,0}; if(poll(&ready,1,500)<=0)continue;
      int fd=accept(listener,nullptr,nullptr); if(fd<0)continue;
      if(active>=8 || workers.size()>=32) { close(fd); continue; } ++active;
      workers.emplace_back([&,fd] { int target=-1;
        try { target=connect_to(argv[5],argv[6]);
          auto session=tls(ctx.get(),server?fd:target,server); proxy(session.get(),server?target:fd);
        } catch(...) { std::cerr << "PAKE connection failed (credentials and payload omitted)\n"; }
        close(fd); if(target>=0)close(target); --active;
      });
    }
    close(listener); for(auto& worker:workers)worker.join(); return 0;
  } catch(...) { std::cerr << "Usage: pake-peer --self-test OR (--server|--client) private-credentials-file numeric-listen-host listen-port target-host target-port\n"; return 2; }
}
