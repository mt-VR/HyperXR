package pojlib.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class GsonUtils {
    public static final Gson GLOBAL_GSON = new GsonBuilder().setPrettyPrinting().create();

    public static <T> T jsonFileToObject(String path, Class<T> tClass) {
        try (Reader reader = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, tClass);
        } catch (IOException | com.google.gson.JsonParseException e) {
            return null;
        }
    }

    public static void objectToJsonFile(String path, Object object) {
        File dir = new File(path).getParentFile();
        if (dir != null) dir.mkdirs();

        try (Writer writer = Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(object, writer);
        } catch (IOException ignored) {}
    }
}
