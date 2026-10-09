#pragma once
#import <WebKit/WebKit.h>

// Avoid the Swift WebKit overlay's strong dependency missing from the iOS 18.5 simulator.
void LilacCallAsyncJavaScript(WKWebView * _Nonnull webView, NSString * _Nonnull script,
                             void (^ _Nonnull completion)(id _Nullable, NSError * _Nullable));
