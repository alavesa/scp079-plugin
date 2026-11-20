package fi.alavesa.scp079.unit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** data.yml: the list of units and the next free id. */
public final class UnitStore {

    private final Path file;
    private final Logger log;
    private int nextId = 1;

    public UnitStore(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public List<Unit> load() {
        List<Unit> units = new ArrayList<>();
        if (!Files.exists(file)) return units;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        nextId = Math.max(1, yaml.getInt("next-id", 1));
        ConfigurationSection section = yaml.getConfigurationSection("units");
        if (section == null) return units;
        for (String key : section.getKeys(false)) {
            ConfigurationSection u = section.getConfigurationSection(key);
            try {
                int id = Integer.parseInt(key);
                units.add(new Unit(id, UUID.fromString(String.valueOf(u.getString("world"))), u.getString("world-name", "?"),
                    u.getDouble("x"), u.getDouble("y"), u.getDouble("z"), (float) u.getDouble("yaw")));
                nextId = Math.max(nextId, id + 1);
            } catch (RuntimeException e) {
                log.warning("data.yml: unit '" + key + "' is not readable and was skipped (" + e.getMessage() + ")");
            }
        }
        return units;
    }

    public int takeId() { return nextId++; }

    /** Writes to a temporary file first, so a crash mid-write cannot leave half a data file. */
    public void save(Collection<Unit> units) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-id", nextId);
        for (Unit unit : units) {
            String path = "units." + unit.id() + ".";
            yaml.set(path + "world", unit.worldId().toString());
            yaml.set(path + "world-name", unit.worldName());
            yaml.set(path + "x", unit.x());
            yaml.set(path + "y", unit.y());
            yaml.set(path + "z", unit.z());
            yaml.set(path + "yaw", (double) unit.yaw());
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, yaml.saveToString());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("Could not save data.yml: " + e.getMessage());
        }
    }
}
