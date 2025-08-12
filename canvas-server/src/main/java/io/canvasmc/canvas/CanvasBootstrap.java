package io.canvasmc.canvas;

import io.papermc.paper.ServerBuildInfo;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.minecraft.SharedConstants;
import net.minecraft.server.Eula;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.Main;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CanvasBootstrap {
    public static final Instant BOOT_TIME = Instant.now();
    public static ComponentLogger LOGGER = ComponentLogger.logger("Canvas");
    public static boolean RUNNING_IN_IDE = false;

    public static OptionSet bootstrap(String[] args) {
        Thread.currentThread().setPriority(9); // higher startup priority

        OptionParser parser = Main.main(args);
        OptionSet options = parser.parse(args);

        if ((options == null) || (options.has("?"))) {
            try {
                parser.printHelpOn(System.out);
            } catch (IOException ex) {
                Logger.getLogger(CanvasBootstrap.class.getName()).log(Level.SEVERE, null, ex);
            }
        } else if (options.has("v")) {
            System.out.println(CraftServer.class.getPackage().getImplementationVersion());
        } else {
            String path = new File(".").getAbsolutePath();
            if (path.contains("!") || path.contains("+")) {
                System.err.println("Cannot run server in a directory with ! or + in the pathname. Please rename the affected folders and try again.");
                System.exit(70);
            }

            boolean skip = Boolean.getBoolean("Paper.IgnoreJavaVersion");
            String javaVersionName = System.getProperty("java.version");
            boolean isPreRelease = javaVersionName.contains("-");
            if (isPreRelease) {
                if (!skip) {
                    System.err.println("Unsupported Java detected (" + javaVersionName + "). You are running an unsupported, non official, version. Only general availability versions of Java are supported. Please update your Java version. See https://docs.papermc.io/paper/faq#unsupported-java-detected-what-do-i-do for more information.");
                    System.exit(70);
                }

                System.err.println("Unsupported Java detected (" + javaVersionName + "), but the check was skipped. Proceed with caution! ");
            }

            try {
                if (options.has("nojline")) {
                    System.setProperty(net.minecrell.terminalconsole.TerminalConsoleAppender.JLINE_OVERRIDE_PROPERTY, "false");
                    Main.useJline = false;
                }

                if (options.has("noconsole")) {
                    Main.useConsole = false;
                    Main.useJline = false;
                    System.setProperty(net.minecrell.terminalconsole.TerminalConsoleAppender.JLINE_OVERRIDE_PROPERTY, "false"); // Paper
                }


                System.setProperty("library.jansi.version", "Paper");
                System.setProperty("jdk.console", "java.base");

                SharedConstants.tryDetectVersion();
                // EULA start - copied from Main
                Path path2 = Paths.get("eula.txt");
                Eula eula = new Eula(path2);
                boolean eulaAgreed = Boolean.getBoolean("com.mojang.eula.agree");
                if (eulaAgreed) {
                    LOGGER.error("You have used the Spigot command line EULA agreement flag.");
                    LOGGER.error("By using this setting you are indicating your agreement to Mojang's EULA (https://aka.ms/MinecraftEULA).");
                    LOGGER.error("If you do not agree to the above EULA please stop your server and remove this flag immediately.");
                }
                if (!eula.hasAgreedToEULA() && !eulaAgreed) {
                    LOGGER.info("You need to agree to the EULA in order to run the server. Go to eula.txt for more info.");
                    System.exit(0);
                }
                // EULA end

                getStartupVersionMessages().forEach(LOGGER::info);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        return options;
    }

    @Contract(pure = true)
    private static @NotNull List<String> asList(String... params) {
        return Arrays.asList(params);
    }

    private static @NotNull @Unmodifiable List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");
        final int availableThreads = Runtime.getRuntime().availableProcessors();

        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        return List.of(
            String.format(
                "Running Java %s (%s %s; %s %s) on %s %s (%s) with %s threads",
                javaSpecVersion,
                javaVmName,
                javaVmVersion,
                javaVendor,
                javaVendorVersion,
                osName,
                osVersion,
                osArch,
                availableThreads
            ),
            String.format(
                "Loading %s %s for Minecraft %s",
                bi.brandName(),
                bi.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL),
                bi.minecraftVersionId()
            ),
            String.format(
                "Running JVM args %s",
                ManagementFactory.getRuntimeMXBean().getInputArguments().toString()
            )
        );
    }
}
