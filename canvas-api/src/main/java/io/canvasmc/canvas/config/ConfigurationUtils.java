package io.canvasmc.canvas.config;

import io.canvasmc.canvas.config.annotation.Comment;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.jetbrains.annotations.NotNull;

public class ConfigurationUtils {

    private static final Map<Class<?>, ConfigData> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private static class ConfigData {
        final Map<String, Comment> comments = new LinkedHashMap<>();
        final Map<String, Field> fieldMap = new LinkedHashMap<>();
        final List<String> keys;

        ConfigData(Class<?> clazz) {
            this.keys = extractKeysRecursive(clazz, "");
        }

        private List<String> extractKeysRecursive(@NotNull Class<?> clazz, String prefix) {
            List<String> keyList = new LinkedList<>();
            for (Field field : clazz.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    String keyName = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
                    keyList.add(keyName);

                    if (field.isAnnotationPresent(Comment.class)) {
                        Comment comment = field.getAnnotation(Comment.class);
                        this.comments.put(keyName, comment);
                    }

                    this.fieldMap.put(keyName, field);
                    if (field.getType().getEnclosingClass() == clazz) {
                        keyList.addAll(extractKeysRecursive(field.getType(), keyName));
                    }
                }
            }
            return Collections.unmodifiableList(keyList);
        }
    }

    private static ConfigData getConfigData(Class<?> clazz) {
        return CACHE.computeIfAbsent(clazz, ConfigData::new);
    }

    public static List<String> extractKeys(Class<?> clazz) {
        return getConfigData(clazz).keys;
    }

    static Map<String, Comment> getComments(Class<?> clazz) {
        return getConfigData(clazz).comments;
    }

    static Map<String, Field> getFieldMap(Class<?> clazz) {
        return getConfigData(clazz).fieldMap;
    }
}
