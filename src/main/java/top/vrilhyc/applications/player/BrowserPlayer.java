package top.vrilhyc.applications.player;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import top.vrilhyc.applications.model.StreamSource;
import top.vrilhyc.applications.model.DanmakuMessage;
import top.vrilhyc.applications.platform.PlatformException;
import top.vrilhyc.applications.player.browser.BrowserMediaServer;
import java.awt.Canvas;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Edge WebView2 + hls.js, embedded in the Java window; no VLC is loaded. */
public final class BrowserPlayer implements LivePlayer {
    private final Canvas canvas;
    private final Consumer<String> status;
    private final Runnable playbackError;
    private final Consumer<String> viewActions;
    private volatile BrowserMediaServer server;
    private volatile boolean danmakuEnabled=true;
    private Process host;
    private Path profile;
    private volatile boolean closed, playing;
    private volatile boolean audioOnly;
    private volatile long nativeHandle;
    private int volume = 30;
    private boolean muted;
    public BrowserPlayer(Canvas canvas, Consumer<String> status, Runnable playbackError) {
        this(canvas,status,playbackError,ignored -> {});
    }
    public BrowserPlayer(Canvas canvas, Consumer<String> status, Runnable playbackError, Consumer<String> viewActions) {
        this.canvas = canvas; this.status = status; this.playbackError = playbackError;
        this.viewActions=viewActions;
        canvas.addHierarchyListener(event -> {
            if (canvas.isDisplayable()) nativeHandle = Pointer.nativeValue(Native.getComponentPointer(canvas));
            else nativeHandle = 0;
        });
    }
    private void initialize() {
        if (host != null && host.isAlive()) return;
        if (!System.getProperty("os.name").startsWith("Windows") || !System.getProperty("os.arch").equals("amd64"))
            throw new PlatformException("浏览器播放器当前支持 Windows x64");
        try {
            Path executable = findHost();
            long handle = nativeHandle;
            if (handle == 0) throw new PlatformException("播放窗口尚未就绪，请重试");
            releaseHost();
            server = new BrowserMediaServer(event -> {
                if(!closed && event.startsWith("view:")) { viewActions.accept(event.substring(5));return; }
                if (closed || !playing) return;
                switch (event) {
                    case "playing" -> status.accept(audioOnly ? "正在播放 · 仅音频流" : "正在播放 · 浏览器内核");
                    case "buffering" -> status.accept("正在缓冲…");
                    case "autoplay" -> status.accept("请点击画面开始播放");
                    case "error" -> { playing = false; playbackError.run(); }
                    default -> {
                        if(event.startsWith("distance:")) status.accept((audioOnly ? "仅音频 · " : "正在播放 · ")+"距流边缘约 "+event.substring(9)+" 秒");
                    }
                }
            });
            profile = Files.createTempDirectory("LiveSpyer-WebView2-");
            host = new ProcessBuilder(executable.toString(),Long.toString(handle),server.page().toString(),profile.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process currentHost = host;
            CompletableFuture<Void> ready = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try (var reader = currentHost.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.equals("READY")) ready.complete(null);
                        if (line.startsWith("ERROR:")) {
                            ready.completeExceptionally(new IOException("Browser initialization failed"));
                            if (!closed && playing && host == currentHost) { playing = false; playbackError.run(); }
                        }
                    }
                    ready.completeExceptionally(new IOException("Browser host exited"));
                    if (!closed && playing && host == currentHost) { playing = false; playbackError.run(); }
                } catch (IOException e) { ready.completeExceptionally(e); }
            });
            ready.get(20,TimeUnit.SECONDS);
        } catch (PlatformException e) { throw e; }
        catch (Exception e) {
            releaseHost();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new PlatformException("浏览器初始化失败，请确认已安装 Microsoft Edge WebView2 Runtime 后重试");
        }
    }
    private static Path findHost() throws Exception {
        var roots = new LinkedHashSet<Path>();
        Path location = Path.of(BrowserPlayer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (location.toString().endsWith(".jar")) {
            roots.add(location.getParent()); // jpackage app/ directory
            roots.add(location.getParent().getParent()); // Gradle lib/ directory
        }
        else for (Path parent = location; parent != null; parent = parent.getParent()) {
            if (parent.getFileName() != null && parent.getFileName().toString().equals("build")) { roots.add(parent.getParent()); break; }
        }
        roots.add(Path.of(System.getProperty("user.dir")));
        for (Path root : roots) for (String relative : List.of("native-host/LiveSpyer.BrowserHost.exe","build/native-host/LiveSpyer.BrowserHost.exe")) {
            Path candidate = root.resolve(relative).toAbsolutePath();
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new PlatformException("未找到浏览器组件，请执行 gradlew.bat buildBrowserHost 或通过 gradlew.bat run 启动");
    }
    @Override public synchronized void play(StreamSource source) {
        if (closed) return;
        initialize(); playing = true;
        server.volume(volume); server.muted(muted); server.danmakuVisible(danmakuEnabled); server.play(source);
        audioOnly = source.audioOnly();
    }
    @Override public synchronized void stop() {
        playing = false;
        if (server != null) server.stop();
    }
    @Override public synchronized void volume(int percent) { volume = Math.clamp(percent,0,100); if (server != null) server.volume(volume); }
    @Override public synchronized void muted(boolean value) { muted = value; if (server != null) server.muted(value); }
    @Override public void showDanmaku(DanmakuMessage message) { var current=server; if(current!=null && !closed) current.danmaku(message); }
    @Override public void danmakuVisible(boolean visible) { danmakuEnabled=visible; var current=server; if(current!=null) current.danmakuVisible(visible); }
    @Override public void goLive() { var current=server; if(current!=null) current.goLive(); }
    private void releaseHost() {
        if (server != null) { server.close(); server = null; }
        Process process = host;
        host = null;
        Path oldProfile = profile;
        profile = null;
        if (process == null) return;
        try { process.getOutputStream().write("close\n".getBytes(StandardCharsets.UTF_8)); process.getOutputStream().flush(); }
        catch (IOException ignored) { }
        Thread.ofVirtual().start(() -> {
            try {
                if (!process.waitFor(3,TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroy);
                    process.destroyForcibly();
                    process.waitFor(2,TimeUnit.SECONDS);
                }
                // Only clean the unique temporary profile created by this instance. No symlinks are followed.
                if (oldProfile != null && oldProfile.getFileName().toString().startsWith("LiveSpyer-WebView2-")) {
                    try (var paths = Files.walk(oldProfile)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                    }
                }
            } catch (IOException | InterruptedException ignored) { }
        });
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; playing = false; releaseHost();
    }
}
