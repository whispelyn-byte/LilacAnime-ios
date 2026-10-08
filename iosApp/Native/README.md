# Native iOS dependencies

Sources/LilacLocalAI/LocalAI.cpp wraps llama.cpp with one shared context, CPU/Metal selection, cancellation, inference metrics and actual model chat templates.

## Pinned llama.cpp and Jinja

- Release b11490, commit 9c2e0e491a822adae1f0b1c831adb4160057d24f.
- scripts/build-native.sh checks the source commit, builds official iOS device arm64 and universal arm64/x86_64 simulator frameworks, and copies the result to ignored Frameworks/llama.xcframework.
- The official release archive lacks the simulator slice required by this app's test suite. The script builds the required slices from source and lowers the deployment target to iOS 16.
- Sources/LilacLocalAI/Jinja/ vendors common/jinja/{caps,lexer,parser,runtime,string,value}.{cpp,h}, utils.h, common/json.{cpp,h}, common/json.hpp and common/unicode.{cpp,h} from that commit.
- Modifications: local include paths; replace the isolated JSON helper's GGML_ASSERT with assert; preserve UTF-8 helpers. The interpreter executes the model's real template, including Gemma 4's full template and enable_thinking.
- llama.cpp/Jinja is MIT. The copyright and complete license are in Jinja/LICENSE.txt. The bundled nlohmann JSON 3.12.0 header retains its copyright; its full MIT license is NLOHMANN-LICENSE.txt.
- New dependency license notices are also included in the app's ThirdPartyNotices.txt.

## Archives

The Xcode project pins okferret/libarchive at b84d41ea6ecd1ab0b0cec6d60baa806808589cd5, with system zlib/bzip2 and its bundled codecs. LIBARCHIVE-LICENSE.txt records libarchive's distribution notice. The pinned framework reads ZIP, 7z and supported RAR variants; encrypted/multipart archives are not a promised input. Extraction streams only subtitle/font regular files into flattened filenames, rejects symlinks, and bounds entry and byte counts.

## Native verification

verify.sh downloads stories260K.gguf from a fixed ggml-org model commit with a SHA-256 check into the **test bundle only**. It checks actual CPU model loading, two generation requests and metrics. This tiny model is a runtime smoke test; it does not measure subtitle quality or prove that multi-gigabyte presets fit every iPhone.

The simulator also evaluates Google's full Gemma 4 chat template and reads ZIP/7z/RAR fixtures. Fixture captions are authored test data.

UTF8Locale.cpp applies and restores a per-thread UTF-8 CTYPE locale during synchronous libarchive extraction. This lets 7z UTF-16 and legacy CP949 ZIP filenames convert correctly when iOS starts in the C locale; the process-wide locale remains untouched.
