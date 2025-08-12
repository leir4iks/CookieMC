package io.canvasmc.canvas.command;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.ChunkFullTask;
import io.canvasmc.canvas.CanvasBootstrap;
import io.canvasmc.canvas.ThreadedBukkitServer;
import io.canvasmc.canvas.TickTimes;
import io.canvasmc.canvas.scheduler.TickScheduler;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.ServerBuildInfoImpl;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.objects.Object2DoubleArrayMap;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.util.HSVLike;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import static net.kyori.adventure.text.Component.text;

public class ThreadedServerHealthDump {
    public static final TextColor HEADER = TextColor.color(79, 164, 240);
    public static final TextColor PRIMARY = TextColor.color(48, 145, 237);
    public static final TextColor PRIME_ALT = TextColor.color(48, 157, 240);
    public static final TextColor SECONDARY = TextColor.color(104, 177, 240);
    public static final TextColor INFORMATION = TextColor.color(145, 198, 243);
    public static final TextColor LIST = TextColor.color(33, 97, 188);
    public static final Component NEW_LINE = text("\n");
    public static final ThreadLocal<DecimalFormat> TWO_DECIMAL_PLACES = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));
    public static final ThreadLocal<DecimalFormat> ONE_DECIMAL_PLACES = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.0"));
    public static final TextColor ORANGE = TextColor.color(255, 165, 0);

    public static boolean dump(@NotNull final CommandSender sender, final String @NotNull [] args) {
        final int lowestRegionsCount;
        if (args.length < 1) {
            lowestRegionsCount = 3;
        } else {
            try {
                lowestRegionsCount = Integer.parseInt(args[0]);
            } catch (final NumberFormatException ex) {
                sender.sendMessage(text("Highest utilisation count '" + args[1] + "' must be an integer", NamedTextColor.RED));
                return true;
            }
        }

        final long currTime = System.nanoTime();
        final int build = ServerBuildInfo.buildInfo().buildNumber().orElse(-1);
        final int maxThreadCount = TickScheduler.getScheduler().scheduler.getCoreThreads().length;
        final double minTps;
        final double medianTps;
        final double maxTps;
        final Object2DoubleArrayMap<TickScheduler.FullTick<?>> taskUtilization = new Object2DoubleArrayMap<>();
        final DoubleArrayList tpsByRegion = new DoubleArrayList();
        for (final TickScheduler.FullTick<?> fullTick : TickScheduler.FullTick.ALL_REGISTERED) {
            if (fullTick.isSleeping()) continue;
            TickTimes timings15 = fullTick.getTickTimes15s();
            tpsByRegion.add(fullTick.getTps15s().getAverage());
            taskUtilization.put(fullTick, timings15.getUtilization());
        }

        tpsByRegion.sort(null);
        if (!tpsByRegion.isEmpty()) {
            minTps = tpsByRegion.getDouble(0);
            maxTps = tpsByRegion.getDouble(tpsByRegion.size() - 1);

            final int middle = tpsByRegion.size() >> 1;
            if ((tpsByRegion.size() & 1) == 0) {
                medianTps = (tpsByRegion.getDouble(middle - 1) + tpsByRegion.getDouble(middle)) / 2.0;
            } else {
                medianTps = tpsByRegion.getDouble(middle);
            }
        } else {
            minTps = medianTps = maxTps = 20.0;
        }

        List<ObjectObjectImmutablePair<TickScheduler.FullTick<?>, Double/*utilization*/>> regionsBellowThreshold = new ArrayList<>();
        taskUtilization.forEach((tick, util) -> regionsBellowThreshold.add(new ObjectObjectImmutablePair<>(tick, util))); // add all tick tasks

        regionsBellowThreshold.sort((p1, p2) -> {
            final double util1 = p1.right();
            final double util2 = p2.right();

            // we want the largest first
            return Double.compare(util2, util1);
        });

        final double genRate = ChunkFullTask.genRate(currTime);
        final double loadRate = ChunkFullTask.loadRate(currTime);

        TextComponent.@NotNull Builder root = text();
        final boolean experimental = ServerBuildInfoImpl.IS_EXPERIMENTAL;
        root.append(
            text()
                .append(text("Server Health Report", HEADER, TextDecoration.BOLD))
                .append(NEW_LINE)
                .append(text(" - ", LIST, TextDecoration.BOLD))
                .append(text("Online Players: ", PRIMARY))
                .append(text(Bukkit.getOnlinePlayers().size(), INFORMATION))
                .append(NEW_LINE)
                .append(text(" - ", LIST, TextDecoration.BOLD))
                .append(text("Build Info: ", PRIMARY))
                .append(text(ServerBuildInfo.buildInfo().brandName() + "-", INFORMATION))
                .append(
                    CanvasBootstrap.RUNNING_IN_IDE ? text("IDE", TextColor.color(230, 65, 170), TextDecoration.BOLD) : (build == -1 ? text("local", TextColor.color(250, 40, 80))
                        .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT, text("Running local/dev build, possibly unstable", TextColor.color(250, 40, 80))))
                        : text(build, experimental ? TextColor.color(255, 119, 6) : INFORMATION)
                        .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT, experimental ?
                            text("Experimental build, use with caution", TextColor.color(255, 119, 6)) : text("Stable build", INFORMATION))))
                )
                .append(NEW_LINE)
                .append(text(" - ", LIST, TextDecoration.BOLD))
                .append(text("Utilization: ", PRIMARY))
                .append(text(ONE_DECIMAL_PLACES.get().format(taskUtilization.values().doubleStream().sum()), getColorForMSPT((taskUtilization.values().doubleStream().sum() / ((double) (maxThreadCount * 100))) * 50.0)))
                .append(text("% / ", PRIMARY))
                .append(text(ONE_DECIMAL_PLACES.get().format(maxThreadCount * 100.0), INFORMATION))
                .append(text("%", PRIMARY))
                .append(NEW_LINE)
                .append(text(" - ", LIST, TextDecoration.BOLD))
                .append(text("Load rate: ", PRIMARY))
                .append(text(TWO_DECIMAL_PLACES.get().format(loadRate) + ", ", INFORMATION))
                .append(text("Gen rate: ", PRIMARY))
                .append(text(TWO_DECIMAL_PLACES.get().format(genRate), INFORMATION))
                .append(NEW_LINE)
                .append(text(" - ", LIST, TextDecoration.BOLD))
                .append(text("Lowest Loop TPS: ", PRIMARY))
                .append(text(TWO_DECIMAL_PLACES.get().format(minTps), getColorForTPS(minTps)))
                .append(NEW_LINE)
                .append(text(" - ", LIST, TextDecoration.BOLD))
                .append(text("Median Loop TPS: ", PRIMARY))
                .append(text(TWO_DECIMAL_PLACES.get().format(medianTps), getColorForTPS(medianTps)))
                .append(NEW_LINE)
                .append(text(" - ", LIST, TextDecoration.BOLD))
                .append(text("Highest Loop TPS: ", PRIMARY))
                .append(text(TWO_DECIMAL_PLACES.get().format(maxTps), getColorForTPS(maxTps)))
                .append(NEW_LINE)
        );
        int len = Math.min(lowestRegionsCount, regionsBellowThreshold.size());
        root.append(text()
            .append(text("Highest " + len + " utilization regions", HEADER, TextDecoration.BOLD))
            .append(NEW_LINE)
        );
        for (int i = 0; i < len; ++i) {
            final ObjectObjectImmutablePair<TickScheduler.FullTick<?>, Double> pair = regionsBellowThreshold.get(i);
            final TickScheduler.FullTick<?> tickTask = pair.left();
            String location = tickTask.toString();
            double mspt5s = Math.min(tickTask.tickTimes5s.getAverage(), ThreadedBukkitServer.getInstance().getScheduler().getTimeBetweenTicks());
            double tps5s = Math.min(tickTask.tps5s.getAverage(), ThreadedBukkitServer.getInstance().getScheduler().getTickRate());
            double util = taskUtilization.getDouble(tickTask);
            Component namedClickable = text(location, INFORMATION);
            if (!tickTask.debugInfo().children().isEmpty()) {
                // don't add a click/hover event if the debug info is empty
                namedClickable = namedClickable.clickEvent(ClickEvent.callback((audience) -> {
                    audience.sendMessage(
                        text()
                            .append(text(tickTask.toString(), HEADER, TextDecoration.BOLD))
                            .build()
                    );
                    audience.sendMessage(tickTask.debugInfo());
                })).hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT, text()
                    .append(text(tickTask.toString(), HEADER, TextDecoration.BOLD))
                    .append(text(" debug info", PRIME_ALT))
                    .build()));
            }
            TextComponent.@NotNull Builder head = text()
                .append(text(" - ", LIST, TextDecoration.BOLD))
                .append(text("TickLoop of ", PRIMARY))
                .append(namedClickable)
                .append(NEW_LINE)

                .append(text()
                    .append(text("    5s: ", PRIMARY, TextDecoration.BOLD))
                    .append(text(ONE_DECIMAL_PLACES.get().format(util), getColorForMSPT((util / 100) * 50.0)))
                    .append(text("% util at ", PRIMARY))
                    .append(text(TWO_DECIMAL_PLACES.get().format(mspt5s), getColorForMSPT(mspt5s)))
                    .append(text(" MSPT at ", PRIMARY))
                    .append(text(TWO_DECIMAL_PLACES.get().format(tps5s), getColorForTPS(tps5s)))
                    .append(text(" TPS", PRIMARY)));
            if (i != len - 1) {
                // last one, don't append
                head.append(NEW_LINE);
            }
            root.append(head.build());
        }
        sender.sendMessage(root.build());
        return true;
    }

    public static @NotNull TextColor getColorForTPS(final double tps) {
        final double maxTps = ThreadedBukkitServer.getInstance().getScheduler().getTickRate();
        final double clamped = Math.min(Math.abs(maxTps - tps), maxTps);
        final double percent = clamped / maxTps;

        final double hue = interpolateHue(percent);
        return TextColor.color(HSVLike.hsvLike((float) (hue / 360.0), 0.85f, 0.80f));
    }

    public static @NotNull TextColor getColorForMSPT(final double mspt) {
        final double idealMspt = ThreadedBukkitServer.getInstance().getScheduler().getTimeBetweenTicks();
        final double clamped = Math.min(mspt, idealMspt * 2);
        final double percent = Math.max(0.0, (clamped - idealMspt) / idealMspt);

        final double hue = interpolateHue(percent);
        return TextColor.color(HSVLike.hsvLike((float) (hue / 360.0), 0.85f, 0.80f));
    }

    public static @NotNull TextColor getColorForPing(final double ping) {
        final double badPing = 300;
        final double clamped = Math.min(ping, badPing * 2);
        final double percent = Math.max(0.0, (clamped - badPing) / badPing);

        final double hue = interpolateHue(percent);
        return TextColor.color(HSVLike.hsvLike((float) (hue / 360.0), 0.85f, 0.80f));
    }

    private static double interpolateHue(double percent) {
        percent = Math.min(Math.max(percent, 0.0), 1.0);
        return 130.0 * (1.0 - percent);
    }

    public static @NotNull Component getTPSColor(double tps) {
        return text(String.format("%.2f", tps)).color(getColorForTPS(tps));
    }

    public static @NotNull Component getMSPTColor(double mspt) {
        return text(String.format("%.2f", mspt)).color(getColorForMSPT(mspt));
    }
}
