#import "NativeWebKit.h"

void LilacCallAsyncJavaScript(WKWebView *webView, NSString *script, void (^completion)(id, NSError *)) {
    [webView callAsyncJavaScript:script arguments:@{} inFrame:nil inContentWorld:WKContentWorld.pageWorld completionHandler:completion];
}
