package ru.bulldog.justmap.map.data;

import ru.bulldog.justmap.client.JustMapClient;
import ru.bulldog.justmap.client.config.ClientParams;
import ru.bulldog.justmap.util.ColorUtil;
import ru.bulldog.justmap.util.Dimension;
import ru.bulldog.justmap.util.tasks.TaskManager;

import net.minecraft.world.World;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.ChunkRandom;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkData {
	
	public final static ChunkLevel EMPTY_LEVEL = new ChunkLevel(-1);
	
	private final static TaskManager chunkUpdater = TaskManager.getManager("chunk-updater", 3);
	
	private final DimensionData mapData;
	private final Map<Layer, ChunkLevel[]> levels = new ConcurrentHashMap<>();
	private final Identifier dimension;
	private final ChunkPos chunkPos;
	private World world;
	private WeakReference<WorldChunk> worldChunk;
	private boolean outdated = false;
	private boolean updating = false;
	private boolean purged = false;
	private boolean slime = false;
	private boolean saved = true;
	private long refreshed = 0;
	
	public boolean saving = false;
	public long updated = 0;
	public long requested = 0;
	
	private Object levelLock = new Object();
	
	public static void updadeChunk(ChunkData mapChunk) {
		if (mapChunk.updating || mapChunk.purged) return;
		chunkUpdater.execute("Updating chunk " + mapChunk.chunkPos, mapChunk::updateChunkData);
	}
	
	public ChunkData(DimensionData data, World world, WorldChunk lifeChunk) {
		this(data, world, lifeChunk.getPos());
		this.updateWorldChunk(lifeChunk);
	}
	
	public ChunkData(DimensionData data, World world, ChunkPos pos) {
		MinecraftClient minecraft = JustMapClient.MINECRAFT;
		RegistryKey<DimensionType> dimType = minecraft.world.getDimensionRegistryKey();
		
		this.mapData = data;
		this.world = world;
		this.dimension = dimType.getValue();
		this.chunkPos = pos;
		this.worldChunk = new WeakReference<>(minecraft.world.getChunk(pos.x, pos.z));

		if (Dimension.isOverworld(dimType) && (world instanceof ServerWorld)) {
			this.slime = ChunkRandom.getSlimeRandom(chunkPos.x, chunkPos.z,
					((ServerWorld) world).getSeed(), 987234911L).nextInt(10) == 0;
		}		
		if (dimension.equals(DimensionType.THE_NETHER_REGISTRY_KEY.getValue())) {
			initLayer(Layer.NETHER);
		} else {
			initLayer(Layer.SURFACE);
			initLayer(Layer.CAVES);
		}
	}
	
	public ChunkData resetChunk() {
		synchronized (levelLock) {
			this.levels.clear();
		}
		this.outdated = true;
		this.updated = 0;
		
		return this;
	}
	
	private void initLayer(Layer layer) {
		int levels = this.world.getDimensionHeight() / layer.height;		
		this.levels.put(layer, new ChunkLevel[levels]);
	}
	
	private ChunkLevel getChunkLevel(Layer layer, int level) {
		synchronized (levelLock) {
			if (!levels.containsKey(layer)) {
				initLayer(layer);
			}
			
			ChunkLevel chunkLevel;
			try {
				chunkLevel = this.levels.get(layer)[level];
				if (chunkLevel == null) {
					chunkLevel = new ChunkLevel(level);
					this.levels.get(layer)[level] = chunkLevel;
				}
			} catch (ArrayIndexOutOfBoundsException ex) {
				chunkLevel = EMPTY_LEVEL;
			}
			return chunkLevel;
		}
	}
	
	public ChunkPos getPos() {
		return this.chunkPos;
	}
	
	public int getX() {
		return this.chunkPos.x;
	}
	
	public int getZ() {
		return this.chunkPos.z;
	}
	
	public int[] getHeighmap(Layer layer, int level) {
		return this.getChunkLevel(layer, level).heightmap;
	}
	
	public WorldChunk getWorldChunk() {
		return this.worldChunk.get();
	}
	
	public BlockState getBlockState(Layer layer, int level, BlockPos pos) {
		return this.getChunkLevel(layer, level).getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
	}
	
	public BlockState setBlockState(Layer layer, int level, BlockPos pos, BlockState blockState) {
		return this.getChunkLevel(layer, level).setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, blockState);
	}
	
	public boolean update(Layer layer, int level, boolean forceUpdate) {
		if (updating || purged) return false;
		if (!outdated && forceUpdate) {
			this.outdated = forceUpdate;
		}
		long currentTime = System.currentTimeMillis();
		if (!outdated && currentTime - updated < ClientParams.chunkUpdateInterval) return false;
		
		CompletableFuture<Boolean> updated = chunkUpdater.run("Updating Chunk: " + chunkPos, future -> {
			WorldChunk worldChunk = this.updateWorldChunk();
			if (worldChunk == null) {
				return () -> future.complete(false);
			}
			return () -> future.complete(this.updateChunkData(worldChunk, layer, level));
		});
		
		return updated.join();
	}
	
	public void updateWorldChunk(WorldChunk lifeChunk) {
		if (lifeChunk != null && !lifeChunk.isEmpty()) {
			this.worldChunk = new WeakReference<>(lifeChunk);
		}
	}
	
	public WorldChunk updateWorldChunk() {
		WorldChunk currentChunk = this.worldChunk.get();
		if(currentChunk == null || currentChunk.isEmpty()) {
			ChunkManager chunkManager = this.world.getChunkManager();
			if (chunkManager == null) return null;
			WorldChunk lifeChunk = chunkManager.getWorldChunk(getX(), getZ());
			if (lifeChunk == null || lifeChunk.isEmpty()) {
				lifeChunk = ChunkDataManager.callSavedChunk(world, chunkPos);
				if (lifeChunk == null) return null;
			}
			this.updateWorldChunk(lifeChunk);
			return lifeChunk;
		}
		return currentChunk;
	}
	
	public ChunkData updateHeighmap(WorldChunk worldChunk, Layer layer, int level) {
		if (worldChunk == null) return this;
		
		boolean waterTint = ClientParams.alternateColorRender && ClientParams.waterTint;
		boolean skipWater = !(ClientParams.hideWater || waterTint);
		
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				int y = MapProcessor.getTopBlockY(worldChunk, layer, level, x, z, skipWater);
				
				int index = x + (z << 4);
				ChunkLevel chunkLevel = this.getChunkLevel(layer, level);
				if (y != -1) {
					chunkLevel.updateHeightmap(x, z, y);
				} else if (getHeighmap(layer, level)[index] != -1) {
					chunkLevel.clear(x, z);					
					this.saved = false;
				}
			}
		}
		
		return this;
	}
	
	private void updateChunkData() {
		WorldChunk worldChunk = this.updateWorldChunk();
		if (worldChunk == null) return;
		
		this.levels.forEach((layer, levels) -> {
			for (int level = 0; level < levels.length; level++) {
				this.updateChunkData(worldChunk, layer, level);
			}
		});
	}
	
	private boolean updateChunkData(WorldChunk worldChunk, Layer layer, int level) {
		this.updating = true;
		
		ChunkData eastChunk = this.mapData.getChunkManager().getChunk(chunkPos.x + 1, chunkPos.z);
		ChunkData southChunk = this.mapData.getChunkManager().getChunk(chunkPos.x, chunkPos.z - 1);		
		WorldChunk eastWorldChunk = eastChunk.updateWorldChunk();
		WorldChunk southWorldChunk = southChunk.updateWorldChunk();
		
		long currentTime = System.currentTimeMillis();
		
		ChunkLevel chunkLevel = this.getChunkLevel(layer, level);
		if (currentTime - chunkLevel.updated > ClientParams.chunkLevelUpdateInterval) {
			this.updateHeighmap(worldChunk, layer, level);
			eastChunk.updateHeighmap(eastWorldChunk, layer, level);
			southChunk.updateHeighmap(southWorldChunk, layer, level);
			
			chunkLevel.updated = currentTime;
		}
		
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				int index = x + (z << 4);
				
				int posX = x + (chunkPos.x << 4);
				int posZ = z + (chunkPos.z << 4);
				int posY = this.getHeighmap(layer, level)[index];
				
				if (posY < 0) continue;
				
				BlockPos blockPos = new BlockPos(posX, posY, posZ);
				BlockState blockState = this.getBlockState(layer, level, blockPos);
				BlockState worldState = worldChunk.getBlockState(blockPos);
				if(outdated || !blockState.equals(worldState) || currentTime - refreshed > 60000) {
					int color = ColorUtil.blockColor(worldChunk, blockPos);
					if (color != -1) {
						int heightDiff = MapProcessor.heightDifference(this, eastChunk, southChunk, layer, level, x, posY, z);
						
						this.setBlockState(layer, level, blockPos, worldState);
						
						int height = layer.height;
						int bottom = 0, baseHeight = 0;
						if (layer == Layer.NETHER) {
							bottom = level * height;
							baseHeight = 128;
						} else if (layer == Layer.SURFACE) {
							bottom = this.world.getSeaLevel();
							baseHeight = 256;
						} else {
							bottom = level * height;
							baseHeight = 32;
						}
						
						float topoLevel = ((float) (posY - bottom) / baseHeight);						
						
						chunkLevel.topomap[index] = (int) (topoLevel * 100);
						chunkLevel.colormap[index] = color;
						chunkLevel.levelmap[index] = heightDiff;
						chunkLevel.colordata[index] = ColorUtil.proccessColor(color, heightDiff, topoLevel);
						
						this.saved = false;
					}
				} else {				
					int color = chunkLevel.colormap[index];
					if (color != -1) {
						int heightDiff = MapProcessor.heightDifference(this, eastChunk, southChunk, layer, level, x, posY, z);
						if (chunkLevel.levelmap[index] != heightDiff) {
							float topoLevel = chunkLevel.topomap[index] / 100F;
							chunkLevel.levelmap[index] = heightDiff;
							chunkLevel.colordata[index] = ColorUtil.proccessColor(color, heightDiff, topoLevel);
							this.saved = false;
						}
					}
				}
			}
		}
		
		this.updated = currentTime;
		this.refreshed = currentTime;
		this.outdated = false;
		this.updating = false;
		
		return this.saveNeeded();
	}
	
	public int[] getColorData(Layer layer, int level) {
		ChunkLevel chunkLevel = this.getChunkLevel(layer, level);
		return chunkLevel.colordata.clone();
	}
	
	public int getBlockColor(Layer layer, int level, int x, int z) {
		int index = x + (z << 4);
		ChunkLevel chunkLevel = this.getChunkLevel(layer, level);
		return chunkLevel.colordata[index];
	}
	
	public boolean saveNeeded() {
		return !this.saved;
	}
	
	public boolean isChunkLoaded() {
		return this.world.getChunkManager().isChunkLoaded(getX(), getZ());
	}
	
	public boolean hasSlime() {
		return this.slime;
	}
	
	public void updateWorld(World world) {
		if (!this.world.equals(world)) {
			this.resetChunk();
			this.world = world;
		}
	}
}
