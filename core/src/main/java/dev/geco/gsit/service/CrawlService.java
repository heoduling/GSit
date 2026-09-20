package dev.geco.gsit.service;

import dev.geco.gsit.GSitMain;
import dev.geco.gsit.api.event.PlayerCrawlEvent;
import dev.geco.gsit.api.event.PlayerStopCrawlEvent;
import dev.geco.gsit.api.event.PrePlayerCrawlEvent;
import dev.geco.gsit.api.event.PrePlayerStopCrawlEvent;
import dev.geco.gsit.model.Crawl;
import dev.geco.gsit.model.StopReason;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class CrawlService {

    private final GSitMain gSitMain;
    private final boolean available;
    private final HashMap<UUID, Crawl> crawls = new HashMap<>();
    private final Set<UUID> startingCrawls = new HashSet<>();
    private int crawlCount = 0;
    private long crawlTime = 0;

    public CrawlService(GSitMain gSitMain) {
        this.gSitMain = gSitMain;
        available = gSitMain.getVersionManager().isNewerOrVersion(1, 18);
    }

    public boolean isAvailable() { return available; }

    public HashMap<UUID, Crawl> getAllCrawls() { return crawls; }

    public boolean isPlayerCrawling(Player player) { synchronized(crawls) { return crawls.containsKey(player.getUniqueId()); } }

    public Crawl getCrawlByPlayer(Player player) { synchronized(crawls) { return crawls.get(player.getUniqueId()); } }

    public java.util.List<Crawl> getCrawlsSnapshot() { synchronized(crawls) { return new ArrayList<>(crawls.values()); } }

    public boolean hasCrawls() { synchronized(crawls) { return !crawls.isEmpty() || !startingCrawls.isEmpty(); } }

    public void removeAllCrawls() { for(Crawl crawl : getCrawlsSnapshot()) stopCrawl(crawl, StopReason.PLUGIN); }

    public Crawl startCrawl(Player player) {
        if(!gSitMain.isAcceptingOperations()) return null;
        UUID playerId = player.getUniqueId();
        synchronized(crawls) {
            if(crawls.containsKey(playerId) || !startingCrawls.add(playerId)) return null;
        }
        try {
            PrePlayerCrawlEvent prePlayerCrawlEvent = new PrePlayerCrawlEvent(player);
            Bukkit.getPluginManager().callEvent(prePlayerCrawlEvent);
            if(prePlayerCrawlEvent.isCancelled() || !gSitMain.isAcceptingOperations()) return null;

            if(gSitMain.getConfigService().CUSTOM_MESSAGE) gSitMain.getMessageService().sendActionBarMessage(player, "Messages.action-crawl-info");

            Crawl crawl = gSitMain.getEntityUtil().createCrawl(player);
            if(crawl == null || !gSitMain.isAcceptingOperations()) return null;

            crawl.start();
            synchronized(crawls) {
                crawls.put(playerId, crawl);
                crawlCount++;
            }
            Bukkit.getPluginManager().callEvent(new PlayerCrawlEvent(crawl));
            return crawl;
        } finally {
            synchronized(crawls) { startingCrawls.remove(playerId); }
        }
    }

    public boolean stopCrawl(Crawl crawl, StopReason stopReason) {
        PrePlayerStopCrawlEvent prePlayerStopCrawlEvent = new PrePlayerStopCrawlEvent(crawl, stopReason);
        Bukkit.getPluginManager().callEvent(prePlayerStopCrawlEvent);
        if(prePlayerStopCrawlEvent.isCancelled() && stopReason.isCancellable()) return false;

        synchronized(crawls) { crawls.remove(crawl.getPlayer().getUniqueId(), crawl); }
        crawl.stop();
        Bukkit.getPluginManager().callEvent(new PlayerStopCrawlEvent(crawl, stopReason));
        synchronized(crawls) { crawlTime += crawl.getLifetimeInNanoSeconds(); }

        return true;
    }

    public int getCrawlCount() { synchronized(crawls) { return this.crawlCount; } }

    public int getCrawlTime() { synchronized(crawls) { return Math.toIntExact(this.crawlTime / 1_000_000_000); } }

    public void resetCrawlStats() {
        synchronized(crawls) {
            crawlCount = 0;
            crawlTime = 0;
        }
    }

}
