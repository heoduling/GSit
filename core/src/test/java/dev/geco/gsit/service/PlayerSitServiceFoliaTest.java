package dev.geco.gsit.service;

import dev.geco.gsit.GSitMain;
import dev.geco.gsit.model.StopReason;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class PlayerSitServiceFoliaTest {

    private static final int ITERATIONS = 10_000;

    @Test
    void teleportCleanupAlwaysRunsOnTheSeatEntityScheduler() throws Exception {
        GSitMain plugin = mock(GSitMain.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.supportsTaskFeature()).thenReturn(true);
        VersionService versionService = mock(VersionService.class);
        when(versionService.isNewerOrVersion(1, 20, 2)).thenReturn(true);
        when(plugin.getVersionManager()).thenReturn(versionService);

        TaskService taskService = new TaskService(plugin);
        when(plugin.getTaskService()).thenReturn(taskService);

        PlayerSitService service = new PlayerSitService(plugin);
        Player source = mock(Player.class);
        Entity seatEntity = mock(Entity.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        ScheduledTask scheduledTask = mock(ScheduledTask.class);
        PluginManager pluginManager = mock(PluginManager.class);

        UUID sourceId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        UUID seatEntityId = UUID.randomUUID();
        when(source.getUniqueId()).thenReturn(sourceId);
        when(seatEntity.getScheduler()).thenReturn(entityScheduler);

        AtomicReference<Thread> ownerThread = new AtomicReference<>();
        AtomicInteger removals = new AtomicInteger();

        try(ExecutorService owner = Executors.newSingleThreadExecutor(r -> new Thread(r, "entity-owner"));
            MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            Future<?> ownerReady = owner.submit(() -> ownerThread.set(Thread.currentThread()));
            ownerReady.get();

            doAnswer(invocation -> {
                assertSame(ownerThread.get(), Thread.currentThread(), "remove() ran outside the entity owner thread");
                removals.incrementAndGet();
                return null;
            }).when(seatEntity).remove();

            doAnswer(invocation -> {
                Consumer<ScheduledTask> callback = invocation.getArgument(1);
                owner.submit(() -> callback.accept(scheduledTask)).get();
                return scheduledTask;
            }).when(entityScheduler).run(eq(plugin), any(), isNull());

            bukkit.when(() -> Bukkit.getEntity(seatEntityId)).thenReturn(seatEntity);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

            assertTimeout(Duration.ofSeconds(30), () -> {
                for(int i = 0; i < ITERATIONS; i++) {
                    passengerStacks(service).put(sourceId, new AbstractMap.SimpleImmutableEntry<>(riderId, List.of(seatEntityId)));
                    service.stopPlayerSit(source, StopReason.TELEPORT, true, false, false);
                }
            });
        }

        assertEquals(ITERATIONS, removals.get());
    }

    @SuppressWarnings("unchecked")
    private static HashMap<UUID, AbstractMap.SimpleImmutableEntry<UUID, List<UUID>>> passengerStacks(PlayerSitService service) throws ReflectiveOperationException {
        Field field = PlayerSitService.class.getDeclaredField("bottomToTopStacks");
        field.setAccessible(true);
        return (HashMap<UUID, AbstractMap.SimpleImmutableEntry<UUID, List<UUID>>>) field.get(service);
    }
}
