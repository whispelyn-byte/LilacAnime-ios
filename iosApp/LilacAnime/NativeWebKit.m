#import "NativeWebKit.h"

void LilacCallAsyncJavaScript(WKWebView *webView, NSString *script, void (^completion)(id, NSError *)) {
    [webView callAsyncJavaScript:script arguments:@{} inFrame:nil inContentWorld:WKContentWorld.pageWorld completionHandler:completion];
}

void LilacCallAsyncJavaScriptWithArguments(WKWebView *webView, NSString *script, NSDictionary<NSString *, id> *arguments, void (^completion)(id, NSError *)) {
    [webView callAsyncJavaScript:script arguments:arguments inFrame:nil inContentWorld:WKContentWorld.pageWorld completionHandler:completion];
}
