package com.jdte.common.integrations;

import com.jdte.common.recipes.GreenhouseCropDefinition;
import com.jdte.setup.JDTEConfig;
import net.darkhax.botanypots.BotanyPotHelper;
import net.darkhax.botanypots.block.BlockEntityBotanyPot;
import net.darkhax.botanypots.data.displaystate.DisplayState;
import net.darkhax.botanypots.data.displaystate.SimpleDisplayState;
import net.darkhax.botanypots.data.displaystate.TransitionalDisplayState;
import net.darkhax.botanypots.data.recipes.crop.BasicCrop;
import net.darkhax.botanypots.data.recipes.crop.Crop;
import net.darkhax.botanypots.data.recipes.soil.BasicSoil;
import net.darkhax.botanypots.data.recipes.soil.Soil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Forge 1.20.1 Botany Pots bridge using its data-recipe API. */
public final class BotanyPotsGreenhouseIntegration {
    private BotanyPotsGreenhouseIntegration() {
    }

    public static GreenhouseCropDefinition find(Level level, ItemStack seed) {
        if (level == null || seed.isEmpty()) return null;
        for (Crop crop : level.getRecipeManager().getAllRecipesFor(BotanyPotHelper.CROP_TYPE.get())) {
            GreenhouseCropDefinition definition = createDefinition(level, seed, crop);
            if (definition != null) return definition;
        }
        return null;
    }

    public static List<DiscoveredCrop> getCrops(Level level) {
        if (level == null) return List.of();
        List<DiscoveredCrop> result = new ArrayList<>();
        for (Crop crop : level.getRecipeManager().getAllRecipesFor(BotanyPotHelper.CROP_TYPE.get())) {
            if (!(crop instanceof BasicCrop basicCrop)) continue;
            for (ItemStack seed : basicCrop.getSeed().getItems()) {
                if (seed.isEmpty()) continue;
                GreenhouseCropDefinition definition = createDefinition(level, seed, crop);
                if (definition != null) result.add(new DiscoveredCrop(crop.getId(), seed.copyWithCount(1), definition));
            }
        }
        return List.copyOf(result);
    }

    private static GreenhouseCropDefinition createDefinition(Level level, ItemStack seed, Crop crop) {
        BlockPos probePos = BlockPos.ZERO;
        if (!crop.matchesLookup(level, probePos, probe(level, probePos), seed)) return null;

        Soil soil = findCompatibleSoil(level, crop);
        if (soil == null) return null;
        int growthWork = Math.max(1, BotanyPotHelper.getRequiredGrowthTicks(
                level, probePos, probe(level, probePos), crop, soil));
        List<ItemStack> previewOutputs = getPreviewOutputs(crop, seed, level, probePos);
        ResourceLocation displayBlock = getDisplayBlock(crop, level, probePos, seed, previewOutputs);

        return new GreenhouseCropDefinition(previewOutputs, displayBlock, displayBlock, false,
                growthWork, JDTEConfig.COMMON.greenhouseGenericFluidCost.get(),
                (serverLevel, pos, tool) -> harvest(serverLevel, pos, crop, soil, growthWork));
    }

    private static Soil findCompatibleSoil(Level level, Crop crop) {
        BlockPos pos = BlockPos.ZERO;
        ProbePot pot = probe(level, pos);
        for (Soil soil : level.getRecipeManager().getAllRecipesFor(BotanyPotHelper.SOIL_TYPE.get())) {
            Iterable<Item> candidates = BuiltInRegistries.ITEM;
            // Custom soils and ingredients can match items outside their display stacks.
            if (soil instanceof BasicSoil basicSoil && soil.getClass() == BasicSoil.class
                    && basicSoil.getIngredient().isVanilla()) {
                candidates = Arrays.stream(basicSoil.getIngredient().getItems())
                        .map(ItemStack::getItem)
                        .distinct()
                        .toList();
            }
            for (Item item : candidates) {
                ItemStack soilItem = item.getDefaultInstance();
                if (!soil.matchesLookup(level, pos, pot, soilItem)) continue;
                if (BotanyPotHelper.canCropGrow(level, pos, pot, soil, crop)) return soil;
            }
        }
        return null;
    }

    private static List<ItemStack> getPreviewOutputs(Crop crop, ItemStack seed, Level level, BlockPos pos) {
        List<ItemStack> outputs = new ArrayList<>();
        if (crop instanceof BasicCrop basicCrop) {
            basicCrop.getResults().forEach(result -> addUnique(outputs, result.getItem()));
        }
        if (outputs.isEmpty()) {
            for (ItemStack stack : BotanyPotHelper.generateDrop(new Random(0), level, pos, probe(level, pos), crop)) {
                addUnique(outputs, stack);
            }
        }
        if (outputs.isEmpty()) outputs.add(seed.copyWithCount(1));
        return List.copyOf(outputs);
    }

    private static ResourceLocation getDisplayBlock(Crop crop, Level level, BlockPos pos,
                                                    ItemStack seed, List<ItemStack> outputs) {
        for (DisplayState display : crop.getDisplayState(level, pos, probe(level, pos))) {
            BlockState state = getDisplayState(display);
            if (state != null) return BuiltInRegistries.BLOCK.getKey(state.getBlock());
        }
        if (seed.getItem() instanceof BlockItem blockItem) return BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
        for (ItemStack output : outputs) {
            if (output.getItem() instanceof BlockItem blockItem) {
                return BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
            }
        }
        return BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT);
    }

    private static BlockState getDisplayState(DisplayState display) {
        if (display instanceof SimpleDisplayState simple) return simple.getRenderState(1.0F);
        if (display instanceof TransitionalDisplayState transitional) {
            List<DisplayState> phases = transitional.phases;
            return phases.isEmpty() ? null : getDisplayState(phases.get(phases.size() - 1));
        }
        return null;
    }

    private static List<ItemStack> harvest(ServerLevel level, BlockPos pos, Crop crop, Soil soil, int growthTicks) {
        ProbePot probe = probe(level, pos);
        if (!BotanyPotHelper.canCropGrow(level, pos, probe, soil, crop)) return List.of();
        return List.copyOf(BotanyPotHelper.generateDrop(new Random(), level, pos, probe, crop));
    }

    private static void addUnique(List<ItemStack> outputs, ItemStack candidate) {
        if (candidate.isEmpty() || outputs.stream().anyMatch(output -> ItemStack.isSameItemSameTags(output, candidate))) return;
        outputs.add(candidate.copy());
    }

    public record DiscoveredCrop(ResourceLocation recipeId, ItemStack seed, GreenhouseCropDefinition definition) {
    }

    private static ProbePot probe(Level level, BlockPos pos) {
        ProbePot probe = new ProbePot(pos);
        probe.setLevel(level);
        return probe;
    }

    /**
     * Botany Pots' data recipes only require the pot for optional custom hooks.
     * A lightweight probe keeps those hooks available without placing a real pot.
     */
    private static final class ProbePot extends BlockEntityBotanyPot {
        private ProbePot(BlockPos pos) {
            super(BlockEntityBotanyPot.POT_TYPE.get(), pos, Blocks.TERRACOTTA.defaultBlockState());
        }
    }
}
