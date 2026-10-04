package com.pla.smart_npc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.pla.smart_npc.SmartNpc;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minimal JSON-backed replacement for Forge's {@code ForgeConfigSpec} that keeps the
 * same {@code .get()} accessors, so every call site in the original Forge source
 * stays untouched. Config files live in the {@code config/} folder next to the world,
 * just like Forge's TOML specs did.
 *
 * <p>The file is rewritten whenever a value is missing or malformed, which mirrors
 * Forge's "correct the config file on load" behaviour.</p>
 */
public final class JsonConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final JsonObject values;
    private final List<Runnable> reloadListeners = new ArrayList<>();

    public JsonConfig(String fileName) {
        this(FabricLoader.getInstance().getConfigDir().resolve(fileName));
    }

    public JsonConfig(Path file) {
        this.file = file;
        this.values = new JsonObject();
    }

    public void load() {
        JsonObject loaded = readFile();
        if (loaded != null) {
            mergeMissingDefaults(loaded);
            writeValues(loaded);
        }
        trySave();
    }

    public void reload() {
        JsonObject loaded = readFile();
        if (loaded != null) {
            mergeMissingDefaults(loaded);
            writeValues(loaded);
        }
        for (Runnable listener : reloadListeners) {
            listener.run();
        }
    }

    public void addReloadListener(Runnable listener) {
        reloadListeners.add(listener);
    }

    public JsonElement raw(String key) {
        return values.get(key);
    }

    // ------------------------------------------------------------------ value types

    public static final class BooleanValue {
        private final JsonObject holder;
        private final String key;
        private final boolean defaultValue;

        private BooleanValue(JsonObject holder, String key, boolean defaultValue) {
            this.holder = holder;
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public boolean get() {
            JsonElement element = holder.get(key);
            if (element instanceof JsonPrimitive primitive && primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            return defaultValue;
        }

        public void set(boolean value) {
            holder.addProperty(key, value);
        }
    }

    public static final class IntValue {
        private final JsonObject holder;
        private final String key;
        private final int defaultValue;

        private IntValue(JsonObject holder, String key, int defaultValue) {
            this.holder = holder;
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public int get() {
            JsonElement element = holder.get(key);
            if (element instanceof JsonPrimitive primitive && primitive.isNumber()) {
                try {
                    return primitive.getAsInt();
                } catch (NumberFormatException ignored) {
                }
            }
            return defaultValue;
        }

        public void set(int value) {
            holder.addProperty(key, value);
        }
    }

    public static final class DoubleValue {
        private final JsonObject holder;
        private final String key;
        private final double defaultValue;

        private DoubleValue(JsonObject holder, String key, double defaultValue) {
            this.holder = holder;
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public double get() {
            JsonElement element = holder.get(key);
            if (element instanceof JsonPrimitive primitive && primitive.isNumber()) {
                try {
                    return primitive.getAsDouble();
                } catch (NumberFormatException ignored) {
                }
            }
            return defaultValue;
        }

        public void set(double value) {
            holder.addProperty(key, value);
        }
    }

    public static final class StringValue {
        private final JsonObject holder;
        private final String key;
        private final String defaultValue;

        private StringValue(JsonObject holder, String key, String defaultValue) {
            this.holder = holder;
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public String get() {
            JsonElement element = holder.get(key);
            if (element instanceof JsonPrimitive primitive && primitive.isString()) {
                return primitive.getAsString();
            }
            return defaultValue;
        }

        public void set(String value) {
            holder.addProperty(key, value);
        }
    }

    /** List-of-numbers value, mirroring {@code ForgeConfigSpec.ConfigValue<List<? extends Number>>}. */
    public static final class NumberListValue {
        private final JsonObject holder;
        private final String key;
        private final List<Number> defaultValue;

        private NumberListValue(JsonObject holder, String key, List<Number> defaultValue) {
            this.holder = holder;
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public List<Number> get() {
            JsonElement element = holder.get(key);
            if (element instanceof JsonArray array) {
                List<Number> numbers = new ArrayList<>(array.size());
                for (JsonElement entry : array) {
                    if (entry instanceof JsonPrimitive primitive && primitive.isNumber()) {
                        numbers.add(primitive.getAsNumber());
                    }
                }
                return numbers;
            }
            return defaultValue;
        }
    }

    /** List-of-strings value, mirroring {@code ForgeConfigSpec.ConfigValue<List<? extends String>>}. */
    public static final class StringListValue {
        private final JsonObject holder;
        private final String key;
        private final List<String> defaultValue;

        private StringListValue(JsonObject holder, String key, List<String> defaultValue) {
            this.holder = holder;
            this.key = key;
            this.defaultValue = defaultValue;
        }

        public List<String> get() {
            JsonElement element = holder.get(key);
            if (element instanceof JsonArray array) {
                List<String> strings = new ArrayList<>(array.size());
                for (JsonElement entry : array) {
                    if (entry instanceof JsonPrimitive primitive && primitive.isString()) {
                        strings.add(primitive.getAsString());
                    }
                }
                return strings;
            }
            return defaultValue;
        }
    }

    // ------------------------------------------------------------------ builder-ish api

    public BooleanValue defineBoolean(String key, boolean defaultValue) {
        if (!values.has(key)) {
            values.addProperty(key, defaultValue);
        }
        return new BooleanValue(values, key, defaultValue);
    }

    public IntValue defineInt(String key, int defaultValue) {
        if (!values.has(key)) {
            values.addProperty(key, defaultValue);
        }
        return new IntValue(values, key, defaultValue);
    }

    public DoubleValue defineDouble(String key, double defaultValue) {
        if (!values.has(key)) {
            values.addProperty(key, defaultValue);
        }
        return new DoubleValue(values, key, defaultValue);
    }

    public StringValue defineString(String key, String defaultValue) {
        if (!values.has(key)) {
            values.addProperty(key, defaultValue);
        }
        return new StringValue(values, key, defaultValue);
    }

    public NumberListValue defineNumberList(String key, List<Number> defaultValue) {
        if (!values.has(key)) {
            values.add(key, copyNumberList(defaultValue));
        }
        return new NumberListValue(values, key, defaultValue);
    }

    /** Defines an arbitrary JSON element default (for example a nested roster object). */
    public void defineElement(String key, JsonElement defaultValue) {
        if (!values.has(key)) {
            values.add(key, defaultValue.deepCopy());
        }
    }

    public StringListValue defineStringList(String key, List<String> defaultValue) {
        if (!values.has(key)) {
            values.add(key, copyStringList(defaultValue));
        }
        return new StringListValue(values, key, defaultValue);
    }

    private static JsonArray copyNumberList(List<Number> list) {
        JsonArray array = new JsonArray();
        for (Number number : list) {
            array.add(number);
        }
        return array;
    }

    private static JsonArray copyStringList(List<String> list) {
        JsonArray array = new JsonArray();
        for (String string : list) {
            array.add(string);
        }
        return array;
    }

    // ------------------------------------------------------------------ io

    private JsonObject readFile() {
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element instanceof JsonObject object) {
                return object;
            }
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            SmartNpc.LOGGER.error("Failed to read config file {}", file, exception);
        }
        return null;
    }

    private void mergeMissingDefaults(JsonObject loaded) {
        for (String key : values.keySet()) {
            if (!loaded.has(key)) {
                loaded.add(key, values.get(key));
            }
        }
    }

    private void writeValues(JsonObject loaded) {
        values.entrySet().clear();
        for (var entry : loaded.entrySet()) {
            values.add(entry.getKey(), entry.getValue());
        }
    }

    private void trySave() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(values, writer);
            }
        } catch (IOException exception) {
            SmartNpc.LOGGER.error("Failed to save config file {}", file, exception);
        }
    }

    public void save() {
        trySave();
    }

    public Path file() {
        return file;
    }

}
