package dev.geco.gsit.service;

import dev.geco.gsit.GSitMain;
import dev.geco.gsit.link.WorldGuardLink;
import dev.geco.gsit.model.Crawl;
import dev.geco.gsit.model.Pose;
import dev.geco.gsit.model.Seat;
import dev.geco.gsit.model.StopReason;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class HotUnloadService implements Listener {

    static final long DRAIN_TICKS = 100L;

    private final GSitMain gSitMain;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final Method asyncCommandDispatcher;

    public HotUnloadService(GSitMain gSitMain) {
        this.gSitMain = gSitMain;
        this.asyncCommandDispatcher = findAsyncCommandDispatcher();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void handlePlayerCommand(PlayerCommandPreprocessEvent event) {
        if(intercept(event.getPlayer(), event.getMessage())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void handleServerCommand(ServerCommandEvent event) {
        if(intercept(event.getSender(), event.getCommand())) event.setCancelled(true);
    }

    boolean intercept(CommandSender sender, String commandLine) {
        Request request = Request.parse(commandLine);
        if(request == null) return false;
        if(!sender.hasPermission("plugman." + request.action())) return false;

        if(request.allPlugins()) {
            sender.sendMessage("§c[GSit] PlugMan all/* operations are blocked. Reload or unload GSit explicitly.");
            return true;
        }
        if(!request.targetsGSit()) return false;
        if(!request.action().equals("reload") && !request.action().equals("unload")) {
            sender.sendMessage("§c[GSit] Use /plugman reload GSit or /plugman unload GSit for a safe lifecycle.");
            return true;
        }
        if(asyncCommandDispatcher == null) {
            sender.sendMessage("§c[GSit] This server core cannot safely resume PlugMan outside the old plugin call stack. Use a full server restart.");
            return true;
        }
        if(!draining.compareAndSet(false, true)) {
            sender.sendMessage("§e[GSit] A safe hot-unload drain is already running.");
            return true;
        }

        beginDrain(sender, request.command());
        return true;
    }

    private void beginDrain(CommandSender sender, String command) {
        gSitMain.beginHotUnloadDrain();
        sender.sendMessage("§e[GSit] Preparing safe hot unload; the PlugMan command will resume in 5 seconds.");

        List<Seat> seats = gSitMain.getSitService().getSeatsSnapshot();
        List<Pose> poses = gSitMain.getPoseService().getPosesSnapshot();
        List<Crawl> crawls = gSitMain.getCrawlService().getCrawlsSnapshot();
        Map<UUID, List<UUID>> playerSitMarkers = gSitMain.getPlayerSitService().getBottomMarkerIdsSnapshot();

        for(Seat seat : seats) {
            Entity marker = seat.getSeatEntity();
            queue(marker, () -> {
                if(gSitMain.getSitService().getSeatByEntity(seat.getEntity()) == seat) gSitMain.getSitService().removeSeatForHotUnload(seat);
                if(marker.isValid()) marker.remove();
            });
        }

        for(Pose pose : poses) {
            Entity marker = pose.getSeat().getSeatEntity();
            queue(marker, () -> {
                if(gSitMain.getPoseService().getPoseByPlayer(pose.getPlayer()) == pose) gSitMain.getPoseService().removePoseForHotUnload(pose);
                if(marker.isValid()) marker.remove();
            });
        }

        for(Crawl crawl : crawls) {
            Player player = crawl.getPlayer();
            queue(player, () -> {
                if(gSitMain.getCrawlService().getCrawlByPlayer(player) == crawl) gSitMain.getCrawlService().stopCrawl(crawl, StopReason.PLUGIN);
            });
        }

        for(Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            queue(player, () -> {
                List<UUID> markerIds = playerSitMarkers.get(player.getUniqueId());
                if(markerIds != null) {
                    gSitMain.getPlayerSitService().stopPlayerSitForHotUnload(player);
                    for(UUID markerId : markerIds) {
                        Entity marker = Bukkit.getEntity(markerId);
                        if(marker != null) queue(marker, () -> { if(marker.isValid()) marker.remove(); });
                    }
                }
                gSitMain.getPacketHandler().removePlayerPacketHandler(player);
                player.removeMetadata(WorldGuardLink.NPC_TAG, gSitMain);
            });
        }

        Bukkit.getGlobalRegionScheduler().runDelayed(gSitMain, task -> finishDrain(sender, command), DRAIN_TICKS);
    }

    private void queue(Entity owner, Runnable cleanup) {
        pending.incrementAndGet();
        AtomicBoolean completed = new AtomicBoolean();
        Runnable done = () -> {
            if(completed.compareAndSet(false, true)) pending.decrementAndGet();
        };
        Runnable task = () -> {
            try {
                cleanup.run();
            } catch(Throwable throwable) {
                failures.incrementAndGet();
                gSitMain.getLogger().log(Level.SEVERE, "Hot-unload owner cleanup failed for " + owner.getUniqueId(), throwable);
            } finally {
                done.run();
            }
        };

        try {
            if(!owner.getScheduler().execute(gSitMain, task, done, 1L)) done.run();
        } catch(Throwable throwable) {
            failures.incrementAndGet();
            done.run();
            gSitMain.getLogger().log(Level.SEVERE, "Could not submit hot-unload owner cleanup for " + owner.getUniqueId(), throwable);
        }
    }

    private void finishDrain(CommandSender sender, String command) {
        boolean empty = !gSitMain.getSitService().hasSeats()
            && !gSitMain.getPlayerSitService().hasPlayerSitStacks()
            && !gSitMain.getPoseService().hasPoses()
            && !gSitMain.getCrawlService().hasCrawls();

        if(pending.get() != 0 || failures.get() != 0 || !empty) {
            String status = "pending=" + pending.get() + ", failures=" + failures.get() + ", stateEmpty=" + empty;
            gSitMain.getLogger().severe("Safe hot unload refused: " + status);
            send(sender, "§c[GSit] Safe hot unload refused (" + status + "). Use a full server restart.");
            return;
        }

        gSitMain.finishHotUnloadResources();
        queueReplay(sender, command);
    }

    private void queueReplay(CommandSender sender, String command) {
        send(sender, "§a[GSit] Owner cleanup completed; PlugMan has been queued on the server command lane.");
        try {
            // CraftServer creates the queued Runnable in the server class loader. PlugMan therefore
            // loads the replacement only after every frame from this class loader has returned.
            asyncCommandDispatcher.invoke(Bukkit.getServer(), Bukkit.getConsoleSender(), command);
        } catch(IllegalAccessException | InvocationTargetException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : exception;
            gSitMain.getLogger().log(Level.SEVERE, "Could not queue PlugMan on the server command lane", cause);
            send(sender, "§c[GSit] PlugMan was not resumed. Use a full server restart.");
        }
    }

    private Method findAsyncCommandDispatcher() {
        try {
            return Bukkit.getServer().getClass().getMethod("dispatchCmdAsync", CommandSender.class, String.class);
        } catch(NoSuchMethodException exception) {
            gSitMain.getLogger().log(Level.WARNING, "Safe PlugMan hot unload is unavailable: dispatchCmdAsync is missing", exception);
            return null;
        }
    }

    private void send(CommandSender sender, String message) {
        if(sender instanceof Player player) {
            try {
                player.getScheduler().execute(gSitMain, () -> player.sendMessage(message), () -> {}, 1L);
            } catch(Throwable throwable) {
                gSitMain.getLogger().log(Level.WARNING, "Could not send hot-unload status to the player", throwable);
            }
            return;
        }
        sender.sendMessage(message);
    }

    record Request(String action, String target, String command) {

        static Request parse(String input) {
            if(input == null) return null;
            String command = input.trim();
            while(command.startsWith("/")) command = command.substring(1);
            String[] args = command.split("\\s+");
            if(args.length < 3) return null;

            String label = args[0].toLowerCase(Locale.ROOT);
            if(!label.equals("plugman") && !label.equals("plm") && !label.equals("plugman:plugman") && !label.equals("plugmanx:plugman")) return null;

            String action = args[1].toLowerCase(Locale.ROOT);
            if(!Set.of("disable", "restart", "reload", "unload").contains(action)) return null;
            return new Request(action, args[2], command);
        }

        boolean targetsGSit() { return target.equalsIgnoreCase(GSitMain.NAME); }

        boolean allPlugins() { return target.equalsIgnoreCase("all") || target.equals("*"); }
    }
}
