package com.aifinalshell;

/**
 * 应用启动器（非 Application 子类）。
 *
 * <p>Java 11+ 在 fat-jar 场景下，若 JVM 的主类继承 {@link javafx.application.Application}，
 * 且 JavaFX 仅位于 classpath（fat-jar 内）而非 module-path，JVM 启动器会在调用 main() 之前
 * 抛出"缺少 JavaFX 运行时组件, 需要使用该组件来运行此应用程序"。</p>
 *
 * <p>使用独立 Launcher 作为主类可规避此检查：Launcher 不继承 Application，JVM 不会触发
 * JavaFX 模块检查；进入 main() 后委托 {@link AiFinalShellApp#main} 执行内部 JRE 检测与
 * Application.launch()，此时 JavaFX 类可从 classpath 正常加载。</p>
 *
 * <p>开发模式（mvn javafx:run）仍以 AiFinalShellApp 为主类，由 javafx-maven-plugin
 * 将 JavaFX 置于 module-path，不受影响。</p>
 */
public class Launcher {
    public static void main(String[] args) {
        AiFinalShellApp.main(args);
    }
}
