package top.vrilhyc.applications.ui;

import top.vrilhyc.applications.platform.PlatformException;
import java.util.concurrent.*;

final class UiErrors {
    static String message(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null)
            error = error.getCause();
        if (error instanceof PlatformException) return error.getMessage();
        if (error instanceof CancellationException || error instanceof InterruptedException) return "操作已取消";
        if (error instanceof TimeoutException || error instanceof java.net.http.HttpTimeoutException) return "请求超时，请稍后重试";
        // Arbitrary exception messages can contain signed URLs or credentials.
        return "操作失败，请检查网络后重试（" + error.getClass().getSimpleName() + "）";
    }
}
