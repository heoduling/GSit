package dev.geco.gsit.service;

import dev.geco.gsit.GSitMain;
import dev.geco.gsit.api.event.PlayerStopPlayerSitEvent;
import dev.geco.gsit.api.event.PrePlayerStopPlayerSitEvent;
import dev.geco.gsit.model.StopReason;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerSitServiceFoliaTest {

    private static final int ITERATIONS = 10_000;

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("directionsAndReasons")
    void everyStopReasonCleansBothDirectionsOnTheMarkerOwner(Direction direction, StopReason reason) throws Exception {
        GSitMain plugin = mock(GSitMain.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.supportsTaskFeature()).thenReturn(true);
        VersionService versionService = mock(VersionService.class);
        when(versionService.isNewerOrVersion(1, 20, 2)).thenReturn(true);
        when(plugin.getVersionManager()).thenReturn(versionService);
        TaskService taskService = new TaskService(plugin);
        when(plugin.getTaskService()).thenReturn(taskService);

        PlayerSitService service = new PlayerSitService(plugin);
        Player bottom = mock(Player.class);
        Player top = mock(Player.class);
        Player source = direction == Direction.BOTTOM_STOPS ? bottom : top;
        Entity marker = mock(Entity.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        ScheduledTask scheduledTask = mock(ScheduledTask.class);
        PluginManager pluginManager = mock(PluginManager.class);

        UUID bottomId = UUID.randomUUID();
        UUID topId = UUID.randomUUID();
        UUID markerId = UUID.randomUUID();
        when(bottom.getUniqueId()).thenReturn(bottomId);
        when(top.getUniqueId()).thenReturn(topId);
        when(marker.getUniqueId()).thenReturn(markerId);
        when(marker.getScheduler()).thenReturn(entityScheduler);

        List<UUID> markerIds = List.of(markerId);
        passengerStacks(service).put(bottomId, new AbstractMap.SimpleImmutableEntry<>(topId, markerIds));
        vehicleStacks(service).put(topId, new AbstractMap.SimpleImmutableEntry<>(bottomId, markerIds));

        List<Event> events = new ArrayList<>();
        AtomicReference<Thread> ownerThread = new AtomicReference<>();
        AtomicInteger removals = new AtomicInteger();

        try(ExecutorService owner = Executors.newSingleThreadExecutor(r -> new Thread(r, "marker-owner"));
            MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            owner.submit(() -> ownerThread.set(Thread.currentThread())).get();

            doAnswer(invocation -> {
                assertSame(ownerThread.get(), Thread.currentThread(), "marker remove() ran outside its Folia owner thread");
                removals.incrementAndGet();
                return null;
            }).when(marker).remove();
            doAnswer(invocation -> {
                Consumer<ScheduledTask> callback = invocation.getArgument(1);
                owner.submit(() -> callback.accept(scheduledTask)).get();
                return scheduledTask;
            }).when(entityScheduler).run(eq(plugin), any(), isNull());
            doAnswer(invocation -> {
                events.add(invocation.getArgument(0));
                return null;
            }).when(pluginManager).callEvent(any(Event.class));

            bukkit.when(() -> Bukkit.getEntity(markerId)).thenReturn(marker);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

            assertTrue(service.stopPlayerSit(source, reason));
        }

        assertFalse(service.hasPlayerSitStacks(), "both direction mappings must be empty");
        assertEquals(0, passengerStacks(service).size());
        assertEquals(0, vehicleStacks(service).size());
        assertEquals(1, removals.get(), "the marker must be removed exactly once");
        assertEquals(2, events.size(), "exactly one pre-event and one post-event are expected");
        PrePlayerStopPlayerSitEvent preEvent = (PrePlayerStopPlayerSitEvent) events.get(0);
        PlayerStopPlayerSitEvent postEvent = (PlayerStopPlayerSitEvent) events.get(1);
        assertSame(source, preEvent.getPlayer());
        assertSame(source, postEvent.getPlayer());
        assertSame(reason, preEvent.getReason());
        assertSame(reason, postEvent.getReason());
        if(direction == Direction.BOTTOM_STOPS) {
            verify(bottom).eject();
            verify(bottom, never()).leaveVehicle();
        } else {
            verify(top).leaveVehicle();
            verify(top, never()).eject();
        }
    }

    private static Stream<Arguments> directionsAndReasons() {
        return Stream.of(Direction.values())
            .flatMap(direction -> Stream.of(StopReason.values()).map(reason -> Arguments.of(direction, reason)));
    }

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

    @SuppressWarnings("unchecked")
    private static HashMap<UUID, AbstractMap.SimpleImmutableEntry<UUID, List<UUID>>> vehicleStacks(PlayerSitService service) throws ReflectiveOperationException {
        Field field = PlayerSitService.class.getDeclaredField("topToBottomStacks");
        field.setAccessible(true);
        return (HashMap<UUID, AbstractMap.SimpleImmutableEntry<UUID, List<UUID>>>) field.get(service);
    }

    private enum Direction {
        BOTTOM_STOPS,
        TOP_STOPS
    }
}
