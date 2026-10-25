/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords.test;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/** Test-only app_process helper. Credential bytes stay between processes on the designated phone. */
public final class GooglePasswordCompare {
    private static final int LIMIT = 1024 * 1024;
    private static final String ORIGIN = "https://passwords.google.com";
    private static final String BRIDGE = "cyc653-approved-password-compare";
    private static final String DEBUG = "chrome_devtools_remote";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static String stage = "bridge";

    public static void main(String[] arguments) {
        try {
            compare();
            System.out.println("Phone-only official reader comparison completed");
        } catch (Throwable failure) {
            // Never emit exception messages, CDP responses, HTML, JSON or credential values.
            System.err.println("Phone-only official reader comparison failed at " + stage);
            System.exit(2);
        }
    }

    private static void compare() throws Exception {
        try (LocalSocket bridge = connect(BRIDGE)) {
            DataInputStream input = new DataInputStream(bridge.getInputStream());
            int length = input.readInt();
            if (length < 1 || length > LIMIT) throw new Exception();
            byte[] packet = new byte[length];
            input.readFully(packet);
            JSONObject expected;
            try { expected = new JSONObject(new String(packet, StandardCharsets.UTF_8)); }
            finally { Arrays.fill(packet, (byte) 0); }
            if (!"https://cyc651.example.invalid/".equals(expected.getString("realm")) ||
                    !"cyc651-synthetic-user".equals(expected.getString("username"))) throw new Exception();
            stage = "target-discovery";
            String path = targetPath();
            JSONObject result;
            try (LocalSocket debug = connect(DEBUG)) {
                stage = "websocket-handshake";
                handshake(debug, path);
                String expression = "(()=>{const visible=e=>e.getClientRects().length>0;" +
                    "const dialogs=Array.from(document.querySelectorAll('[role=dialog]')).filter(visible);" +
                    "if(location.origin!=='https://passwords.google.com'||dialogs.length!==1||" +
                    "!document.body.innerText.includes('cyc651.example.invalid'))return{ready:false};" +
                    "const root=dialogs[0];const inputs=Array.from(root.querySelectorAll('input')).filter(visible);" +
                    "if(!inputs.some(e=>e.type==='text'&&e.value==='cyc651-synthetic-user'))return{ready:false};" +
                    "const p=inputs.filter(e=>e.type==='password');if(p.length!==1)return{ready:false};" +
                    "return{ready:true,masked:p[0].type==='password',present:p[0].value.length>0,password:p[0].value};})()";
                JSONObject parameters = new JSONObject().put("expression", expression).put("returnByValue", true)
                    .put("silent", true).put("generatePreview", false).put("objectGroup", "cyc653-private-compare");
                stage = "private-evaluation";
                send(debug.getOutputStream(), 1, new JSONObject().put("id", 1).put("method", "Runtime.evaluate")
                    .put("params", parameters).toString().getBytes(StandardCharsets.UTF_8));
                JSONObject response = reply(debug, 1);
                JSONObject evaluation = response.getJSONObject("result");
                if (evaluation.has("exceptionDetails")) throw new Exception();
                result = evaluation.getJSONObject("result").getJSONObject("value");
                // Primitive results have no object handle. Release the isolated group as well.
                send(debug.getOutputStream(), 1, new JSONObject().put("id", 2).put("method", "Runtime.releaseObjectGroup")
                    .put("params", new JSONObject().put("objectGroup", "cyc653-private-compare")).toString().getBytes(StandardCharsets.UTF_8));
            }
            byte[] wanted = expected.getString("password").getBytes(StandardCharsets.UTF_8);
            byte[] actual = result.optString("password", "").getBytes(StandardCharsets.UTF_8);
            boolean matches;
            try { matches = MessageDigest.isEqual(wanted, actual); }
            finally { Arrays.fill(wanted, (byte) 0); Arrays.fill(actual, (byte) 0); }
            expected.remove("password"); result.remove("password");
            stage = "boolean-result";
            DataOutputStream output = new DataOutputStream(bridge.getOutputStream());
            output.writeBoolean(result.optBoolean("ready", false));
            output.writeBoolean(result.optBoolean("masked", false));
            output.writeBoolean(result.optBoolean("present", false));
            output.writeBoolean(matches);
            output.flush();
        }
    }

    private static LocalSocket connect(String name) throws Exception {
        LocalSocket socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
        socket.setSoTimeout(10000);
        return socket;
    }

    private static String targetPath() throws Exception {
        try (LocalSocket socket = connect(DEBUG)) {
            socket.getOutputStream().write(("GET /json/list HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            String headers = headers(socket.getInputStream());
            if (!headers.startsWith("HTTP/1.1 200")) throw new Exception();
            int length = -1;
            for (String line : headers.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
            if (length < 0 || length > LIMIT) throw new Exception();
            byte[] body = new byte[length];
            new DataInputStream(socket.getInputStream()).readFully(body);
            JSONArray targets = new JSONArray(new String(body, StandardCharsets.UTF_8));
            Arrays.fill(body, (byte) 0);
            String path = null;
            for (int i = 0; i < targets.length(); i++) {
                JSONObject target = targets.getJSONObject(i);
                java.net.URI uri = new java.net.URI(target.optString("url", ""));
                if (!"page".equals(target.optString("type")) || !ORIGIN.equals(origin(uri))) continue;
                if (path != null) throw new Exception();
                java.net.URI websocket = new java.net.URI(target.getString("webSocketDebuggerUrl"));
                path = websocket.getRawPath();
                if (path == null || !path.startsWith("/devtools/page/") || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) throw new Exception();
            }
            if (path == null) throw new Exception();
            return path;
        }
    }
    private static String origin(java.net.URI uri) {
        if (uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) return "";
        return uri.getScheme() + "://" + uri.getHost();
    }
    private static void handshake(LocalSocket socket, String path) throws Exception {
        byte[] nonce = new byte[16]; RANDOM.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n" +
            "Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " + key + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        String response = headers(socket.getInputStream());
        if (!response.startsWith("HTTP/1.1 101")) throw new Exception();
        String wanted = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        String actual = null;
        for (String line : response.split("\r\n")) if (line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-accept:")) {
            actual = line.substring(line.indexOf(':') + 1).trim();
        }
        if (!wanted.equals(actual)) throw new Exception();
    }
    private static String headers(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int tail = 0;
        while (bytes.size() < 32768) {
            int value = input.read(); if (value < 0) throw new Exception();
            bytes.write(value); tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
        }
        throw new Exception();
    }
    private static void send(OutputStream output, int opcode, byte[] payload) throws Exception {
        if (payload.length > 65535) throw new Exception();
        output.write(0x80 | opcode);
        if (payload.length < 126) output.write(0x80 | payload.length);
        else { output.write(0x80 | 126); output.write(payload.length >>> 8); output.write(payload.length & 255); }
        byte[] mask = new byte[4]; RANDOM.nextBytes(mask); output.write(mask);
        for (int i = 0; i < payload.length; i++) output.write(payload[i] ^ mask[i % 4]);
        output.flush();
    }
    private static JSONObject reply(LocalSocket socket, int id) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        for (int frame = 0; frame < 64; frame++) {
            int first = input.readUnsignedByte(), second = input.readUnsignedByte();
            if ((first & 0x70) != 0 || (second & 0x80) != 0) throw new Exception();
            int opcode = first & 15;
            long length = second & 127;
            if (length == 126) length = input.readUnsignedShort();
            else if (length == 127) length = input.readLong();
            if (length < 0 || length > LIMIT || length + message.size() > LIMIT) throw new Exception();
            byte[] payload = new byte[(int) length]; input.readFully(payload);
            if (opcode == 9) { send(socket.getOutputStream(), 10, payload); continue; }
            if (opcode != 1 && opcode != 0) throw new Exception();
            message.write(payload); Arrays.fill(payload, (byte) 0);
            if ((first & 0x80) != 0) {
                byte[] body = message.toByteArray();
                JSONObject result;
                try { result = new JSONObject(new String(body, StandardCharsets.UTF_8)); }
                finally { Arrays.fill(body, (byte) 0); }
                message.reset();
                if (result.optInt("id", -1) == id) {
                    if (result.has("error")) throw new Exception();
                    return result;
                }
            }
        }
        throw new Exception();
    }
}
