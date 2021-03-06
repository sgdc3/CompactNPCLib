package org.inventivetalent.npclib;

import com.google.common.base.Joiner;
import com.mojang.authlib.GameProfile;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.inventivetalent.apihelper.API;
import org.inventivetalent.npclib.entity.NPCEntity;
import org.inventivetalent.npclib.event.NPCInteractEvent;
import org.inventivetalent.npclib.npc.NPCAbstract;
import org.inventivetalent.npclib.npc.living.human.NPCPlayer;
import org.inventivetalent.npclib.registry.NPCRegistry;
import org.inventivetalent.packetlistener.handler.PacketHandler;
import org.inventivetalent.packetlistener.handler.PacketOptions;
import org.inventivetalent.packetlistener.handler.ReceivedPacket;
import org.inventivetalent.packetlistener.handler.SentPacket;
import org.inventivetalent.reflection.minecraft.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NPCLib implements API {

	public static Logger logger = Logger.getLogger("NPCLib");
	public static boolean debug;

	public static NPCRegistry createRegistry(@NonNull Plugin plugin) {
		//		return Reflection.newInstance("org.inventivetalent.npc.registry", "NPCRegistry", NPCRegistry.class, plugin);
		return new NPCRegistry(plugin);
	}

	public static boolean isNPC(Entity entity) {
		if (entity == null) { return false; }
		try {
			return Minecraft.getHandle(entity) instanceof NPCEntity;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static NPCAbstract<?, ?> getNPC(Entity entity) {
		if (entity == null) { return null; }
		try {
			Object handle = Minecraft.getHandle(entity);
			if (handle instanceof NPCEntity) {
				return ((NPCEntity) handle).getNPC();
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	public static void debug(Object... message) {
		if (debug) { logger.info("[DEBUG] " + Joiner.on(" ").join(message)); }
	}

	public static final String getVersion() {
		try (InputStream in = NPCLib.class.getResourceAsStream("/npclib.properties")) {
			Properties properties = new Properties();
			properties.load(in);
			return properties.getProperty("npclib.version");
		} catch (IOException e) {
			logger.log(Level.WARNING, "Failed to get API version from npclib.properties", e);
			return "0.0.0";
		}
	}

	@Override
	public void load() {
		long start = System.currentTimeMillis();
		Class<?>[] classes = new Class[NPCType.values().length];
		for (int i = 0; i < classes.length; ) {
			if (NPCType.values()[i].isAvailable()) {
				classes[i] = NPCType.values()[i].getNpcClass();
			}
			i++;
		}
		NPCRegistry.injectClasses(classes);
		long end = System.currentTimeMillis();
		long diff = end - start;
		logger.info("Generated & Injected available entity classes in " + (diff / 1000D) + "s");
	}

	@Override
	public void init(final Plugin plugin) {
		PacketHandler.addHandler(new PacketHandler(plugin) {
			@PacketOptions(forcePlayer = true)
			@Override
			public void onSend(final SentPacket sentPacket) {
				if (sentPacket.hasPlayer()) {
					if ("PacketPlayOutNamedEntitySpawn".equals(sentPacket.getPacketName())) {
						final Player player = sentPacket.getPlayer();
						final UUID uuid = Minecraft.VERSION.newerThan(Minecraft.Version.v1_8_R1) ?
								((UUID) sentPacket.getPacketValue("b")) :
								(((GameProfile) sentPacket.getPacketValue("b")).getId());
						Player npcPlayer = null;
						//TODO: check if this doesn't cause any ConcurrentModExceptions / make this synchronous somehow
						for (Player worldPlayer : player.getWorld().getPlayers()) {// We can't use Bukkit#getOnlinePlayers, since the server doesn't know about the player NPCs
							if (worldPlayer.getUniqueId().equals(uuid)) {
								npcPlayer = worldPlayer;
								break;
							}
						}
						if (npcPlayer != null) {
							NPCAbstract<?, ?> npcAbstract = NPCLib.getNPC(npcPlayer);
							if (npcAbstract != null && npcAbstract instanceof NPCPlayer) {
								((NPCPlayer) npcAbstract).updateToPlayer(player);
							}
						}
					}
				}
			}

			@PacketOptions(forcePlayer = true)
			@Override
			public void onReceive(ReceivedPacket receivedPacket) {
				if (receivedPacket.hasPlayer()) {
					if ("PacketPlayInUseEntity".equals(receivedPacket.getPacketName())) {
						int a = (int) receivedPacket.getPacketValue(0);
						Entity entity = Reflection.getEntityById(receivedPacket.getPlayer().getWorld(), a);
						if (entity == null || !NPCLib.isNPC(entity)) {
							return;
						}
						Enum<?> action = (Enum<?>) receivedPacket.getPacketValue(1);

						NPCInteractEvent event = new NPCInteractEvent(NPCLib.getNPC(entity), a, action == null ? -1 : action.ordinal(), receivedPacket.getPlayer());
						Bukkit.getPluginManager().callEvent(event);
						if (event.isCancelled()) {
							receivedPacket.setCancelled(true);
						}
					}
				}
			}
		});

		String version = getVersion();
		logger.info("Version is " + version);
	}

	@Override
	public void disable(Plugin plugin) {
	}

}
