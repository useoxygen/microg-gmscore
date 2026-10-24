// Prints recognized text lines with centre coordinates in image pixels: "x y text".
import Vision; import AppKit
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let img = NSImage(contentsOf: url)!; var r = CGRect(origin: .zero, size: img.size)
let cg = img.cgImage(forProposedRect: &r, context: nil, hints: nil)!
let W = Double(cg.width), H = Double(cg.height)
let req = VNRecognizeTextRequest(); req.recognitionLevel = .accurate; req.usesLanguageCorrection = false
try! VNImageRequestHandler(cgImage: cg).perform([req])
for o in req.results ?? [] { guard let t = o.topCandidates(1).first else { continue }
  let b = o.boundingBox; print(Int(b.midX * W), Int((1 - b.midY) * H), t.string) }
