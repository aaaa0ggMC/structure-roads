package local.structureroads;

import java.io.File;
import net.minecraftforge.common.config.Configuration;

public final class RoadConfig {
    static int radius, nodesPerTick, maxNodes, blocksPerTick, margin;
    static String[] targetNames, protectionNames, rcTargets;
    static String[] settlementNames, rcSettlements;
    static String[] cqrSettlements;
    static int roadsPerSettlement, branchMin, branchMax, surveyColumns;

    static void load(File file) {
        Configuration c = new Configuration(file);
        c.load();
        radius = c.getInt("localConnectionRadius", "general", 256, 32, 512,
                "Nearby buildings only. No long-distance village network or terrain generation.");
        roadsPerSettlement = c.getInt("roadsPerSettlement", "general", 2, 1, 3,
                "At most this many local connections/abandoned branches per settlement.");
        branchMin = c.getInt("abandonedMinLength", "general", 96, 48, 192, "Seeded branch goal minimum length.");
        branchMax = Math.max(branchMin, c.getInt("abandonedMaxLength", "general", 160, 48, 256,
                "Seeded branch goal maximum length; may end earlier at unsafe/unknown terrain."));
        surveyColumns = c.getInt("surveyColumnsPerTick", "performance", 128, 16, 256,
                "Demand-driven terrain snapshot budget. Already loaded chunks only; 2ms soft deadline.");
        nodesPerTick = c.getInt("nodesPerTick", "performance", 8, 1, 64,
                "Maximum A* expansions per world tick, on the server thread.");
        maxNodes = c.getInt("maxSearchNodes", "performance", 3000, 64, 8000,
                "Maximum visited nodes per route; unsuccessful routes wait before retrying.");
        blocksPerTick = c.getInt("columnsPerTick", "performance", 4, 1, 32,
                "Maximum road columns revalidated and changed per tick.");
        margin = c.getInt("structureMargin", "safety", 5, 2, 24,
                "Horizontal building clearance, in addition to the road half-width.");
        settlementNames = c.getStringList("settlementStructureData", "adapters", new String[] {"Village"},
                "Origins: MapGenStructureData IDs of communal settlements, including vanilla-format village replacements.");
        targetNames = c.getStringList("targetStructureData", "adapters",
                new String[] {"Village", "Temple", "Mansion"},
                "MapGenStructureData identifiers; includes compatible Village Names villages."
                + " Only structures reaching the surface become targets.");
        protectionNames = c.getStringList("protectionStructureData", "adapters",
                new String[] {"Mineshaft", "Stronghold", "Monument", "Fortress", "EndCity"},
                "Avoid these structures but do not automatically connect them.");
        rcTargets = c.getStringList("recurrentComplexTargets", "adapters", new String[0],
                "Exact RC structure IDs allowed as destinations; all RC structures are avoided."
                + " Empty by default: do not connect every tree or decorative ruin.");
        rcSettlements = c.getStringList("recurrentComplexSettlements", "adapters", new String[0],
                "Exact RC template IDs of settlements allowed to originate roads; no guessing by template name.");
        cqrSettlements = c.getStringList("cqrSettlements", "adapters", new String[0],
                "Exact CQR protection-region names of settlements allowed to originate roads. Other surface regions are destinations only.");
        if (c.hasChanged()) c.save();
    }

    private RoadConfig() { }
}
