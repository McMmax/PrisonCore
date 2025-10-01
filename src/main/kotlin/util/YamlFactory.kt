package twizzy.tech.util

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files

/**
 * A utility class for managing YAML configuration files and language files.
 */
class YamlFactory {
    companion object {
        private val yaml: Yaml by lazy {
            val options = DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                isPrettyFlow = true
                indent = 2
            }
            Yaml(options)
        }

        // Language configuration cache
        private var languageConfig: Map<String, Any>? = null
        private var languageFile: File? = null

        /**
         * Initializes the language system by loading the lang.yaml file.
         * @param langFilePath The path to the language file (defaults to "src/main/resources/lang.yaml").
         */
        fun initializeLanguage(langFilePath: String = "lang.yml") {
            languageFile = createConfigIfNotExists(langFilePath, "lang.yml")
            reloadLanguage()
        }

        /**
         * Reloads the language configuration from the file.
         */
        fun reloadLanguage() {
            languageFile?.let { file ->
                languageConfig = loadConfig(file)
            }
        }

        /**
         * Gets a message from the language file.
         * @param path The path to the message (dot notation, e.g., "commands.world.create.success").
         * @param placeholders A map of placeholder names to their values.
         * @return The formatted message or the path if not found.
         */
        fun getMessage(path: String, placeholders: Map<String, Any> = emptyMap()): String {
            val config = languageConfig ?: return path
            val message = getValue(config, path, path)

            return if (message is String) {
                formatMessage(message, placeholders)
            } else {
                path
            }
        }

        /**
         * Gets a list of messages from the language file.
         * @param path The path to the message list (dot notation).
         * @param placeholders A map of placeholder names to their values.
         * @return The formatted message list or empty list if not found.
         */
        @Suppress("UNCHECKED_CAST")
        fun getMessageList(path: String, placeholders: Map<String, Any> = emptyMap()): List<String> {
            val config = languageConfig ?: return emptyList()
            val messages = getValue(config, path, emptyList<String>())

            return if (messages is List<*>) {
                messages.filterIsInstance<String>().map { formatMessage(it, placeholders) }
            } else {
                emptyList()
            }
        }

        /**
         * Formats a message by replacing placeholders with their values and converting color codes.
         * @param message The message template.
         * @param placeholders A map of placeholder names to their values.
         * @return The formatted message with color codes converted.
         */
        fun formatMessage(message: String, placeholders: Map<String, Any>): String {
            var formatted = message
            // Replace placeholders
            for ((key, value) in placeholders) {
                formatted = formatted.replace("{$key}", value.toString())
            }
            // Convert & color codes to § format
            formatted = formatted.replace("&", "§")
            return formatted
        }

        /**
         * Gets a formatted command help message.
         * @param commandName The name of the command.
         * @param placeholders Additional placeholders for the help message.
         * @return The formatted help message with header and usage list.
         */
        fun getCommandHelp(commandName: String, placeholders: Map<String, Any> = emptyMap()): List<String> {
            val helpMessages = mutableListOf<String>()

            // Add header
            val header = getMessage("commands.$commandName.help.header", placeholders)
            helpMessages.add(header)

            // Add usage lines
            val usage = getMessageList("commands.$commandName.help.usage", placeholders)
            helpMessages.addAll(usage)

            return helpMessages
        }

        /**
         * Creates a YAML config file from the given template if it doesn't exist.
         * @param path The path where the config file should be created.
         * @param templatePath The path to the template resource file.
         * @return The config file.
         */
        fun createConfigIfNotExists(path: String, templatePath: String? = null): File {
            val file = File(path)
            if (!file.exists()) {
                file.parentFile?.mkdirs()

                if (templatePath != null) {
                    // Copy from template resource if provided
                    val inputStream = YamlFactory::class.java.classLoader.getResourceAsStream(templatePath)
                    if (inputStream != null) {
                        Files.copy(inputStream, file.toPath())
                    } else {
                        file.createNewFile()
                    }
                } else {
                    file.createNewFile()
                }
            }
            return file
        }

        /**
         * Loads a YAML configuration from a file.
         * @param file The file to load from.
         * @return The loaded configuration as a Map.
         */
        @Suppress("UNCHECKED_CAST")
        fun loadConfig(file: File): Map<String, Any> {
            return FileReader(file).use { reader ->
                yaml.load(reader) as? Map<String, Any> ?: emptyMap()
            }
        }

        /**
         * Loads a YAML configuration from a path.
         * @param path The path to load from.
         * @return The loaded configuration as a Map.
         */
        fun loadConfig(path: String): Map<String, Any> {
            return loadConfig(File(path))
        }

        /**
         * Saves a configuration to a YAML file.
         * @param data The configuration data to save.
         * @param file The file to save to.
         */
        fun saveConfig(data: Any, file: File) {
            FileWriter(file).use { writer ->
                yaml.dump(data, writer)
            }
        }

        /**
         * Saves a configuration to a YAML file at the specified path.
         * @param data The configuration data to save.
         * @param path The path to save to.
         */
        fun saveConfig(data: Any, path: String) {
            saveConfig(data, File(path))
        }

        /**
         * Gets a value from the configuration by its path.
         * @param config The configuration to get the value from.
         * @param path The path to the value (dot notation, e.g., "server.port").
         * @param defaultValue The default value to return if the path is not found.
         * @return The value at the path or the default value.
         */
        @Suppress("UNCHECKED_CAST")
        fun <T> getValue(config: Map<String, Any>, path: String, defaultValue: T): T {
            val parts = path.split(".")
            var current: Any? = config

            for (part in parts) {
                if (current is Map<*, *>) {
                    current = (current as Map<String, Any>)[part]
                    if (current == null) {
                        return defaultValue
                    }
                } else {
                    return defaultValue
                }
            }

            return try {
                current as T
            } catch (e: ClassCastException) {
                defaultValue
            }
        }

        /**
         * Sets a value in the configuration at the specified path.
         * @param config The mutable configuration to modify.
         * @param path The path where to set the value (dot notation).
         * @param value The value to set.
         * @return True if the value was set successfully, false otherwise.
         */
        @Suppress("UNCHECKED_CAST")
        fun setValue(config: MutableMap<String, Any>, path: String, value: Any): Boolean {
            val parts = path.split(".")

            if (parts.size == 1) {
                config[parts[0]] = value
                return true
            }

            var current = config
            for (i in 0 until parts.size - 1) {
                val part = parts[i]
                var next = current[part] as? MutableMap<String, Any>

                if (next == null) {
                    next = mutableMapOf()
                    current[part] = next
                }

                current = next
            }

            current[parts.last()] = value
            return true
        }

        /**
         * Updates a configuration file with new values.
         * @param filePath The path to the configuration file.
         * @param updates A map of paths to their new values.
         * @return True if updates were successful, false otherwise.
         */
        fun updateConfigFile(filePath: String, updates: Map<String, Any>): Boolean {
            val file = File(filePath)
            if (!file.exists()) {
                return false
            }

            val config = loadConfig(file).toMutableMap()

            for ((path, value) in updates) {
                setValue(config, path, value)
            }

            saveConfig(config, file)
            return true
        }
    }
}