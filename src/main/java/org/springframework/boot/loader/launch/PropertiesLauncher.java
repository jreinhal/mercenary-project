/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.launch;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.loader.launch.Archive;
import org.springframework.boot.loader.launch.ExplodedArchive;
import org.springframework.boot.loader.launch.JarFileArchive;
import org.springframework.boot.loader.launch.JarLauncher;
import org.springframework.boot.loader.launch.LaunchedClassLoader;
import org.springframework.boot.loader.launch.Launcher;
import org.springframework.boot.loader.launch.SystemPropertyUtils;
import org.springframework.boot.loader.log.DebugLogger;
import org.springframework.boot.loader.net.protocol.jar.JarUrl;

public class PropertiesLauncher
extends Launcher {
    public static final String MAIN = "loader.main";
    public static final String PATH = "loader.path";
    public static final String HOME = "loader.home";
    public static final String ARGS = "loader.args";
    public static final String CONFIG_NAME = "loader.config.name";
    public static final String CONFIG_LOCATION = "loader.config.location";
    public static final String SET_SYSTEM_PROPERTIES = "loader.system";
    private static final URL[] NO_URLS = new URL[0];
    private static final Pattern WORD_SEPARATOR = Pattern.compile("\\W+");
    private static final String NESTED_ARCHIVE_SEPARATOR = "!" + File.separator;
    private static final String JAR_FILE_PREFIX = "jar:file:";
    private static final DebugLogger debug = DebugLogger.get(PropertiesLauncher.class);
    private final Archive archive;
    private final File homeDirectory;
    private final List<String> paths;
    private final Properties properties = new Properties();

    public PropertiesLauncher() throws Exception {
        this(Archive.create(Launcher.class));
    }

    PropertiesLauncher(Archive archive) throws Exception {
        this.archive = archive;
        this.homeDirectory = this.getHomeDirectory();
        this.initializeProperties();
        this.paths = this.getPaths();
    }

    protected File getHomeDirectory() throws Exception {
        return new File(this.getPropertyWithDefault(HOME, "${user.dir}"));
    }

    private void initializeProperties() throws Exception {
        ArrayList<Object> configs = new ArrayList<Object>();
        if (this.getProperty(CONFIG_LOCATION) != null) {
            configs.add(this.getProperty(CONFIG_LOCATION));
        } else {
            String[] names;
            for (String name : names = this.getPropertyWithDefault(CONFIG_NAME, "loader").split(",")) {
                String propertiesFile = name + ".properties";
                configs.add("file:" + this.homeDirectory + "/" + propertiesFile);
                configs.add("classpath:" + propertiesFile);
                configs.add("classpath:BOOT-INF/classes/" + propertiesFile);
            }
        }
        for (String string : configs) {
            InputStream resource = this.getResource(string);
            try {
                if (resource == null) {
                    debug.log("Not found: %s", string);
                    continue;
                }
                debug.log("Found: %s", string);
                this.loadResource(resource);
                return;
            }
            finally {
                if (resource == null) continue;
                resource.close();
            }
        }
    }

    private InputStream getResource(String config) throws Exception {
        if (config.startsWith("classpath:")) {
            return this.getClasspathResource(config.substring("classpath:".length()));
        }
        if (this.isUrl(config = this.handleUrl(config))) {
            return this.getURLResource(config);
        }
        return this.getFileResource(config);
    }

    private InputStream getClasspathResource(String config) {
        config = this.stripLeadingSlashes((String)config);
        config = "/" + (String)config;
        debug.log("Trying classpath: %s", config);
        return this.getClass().getResourceAsStream((String)config);
    }

    private String handleUrl(String path) {
        if ((path.startsWith(JAR_FILE_PREFIX) || path.startsWith("file:")) && (path = URLDecoder.decode(path, StandardCharsets.UTF_8)).startsWith("file:") && (path = path.substring("file:".length())).startsWith("//")) {
            path = path.substring(2);
        }
        return path;
    }

    private boolean isUrl(String config) {
        return config.contains("://");
    }

    private InputStream getURLResource(String config) throws Exception {
        URL url = new URL(config);
        if (this.exists(url)) {
            URLConnection connection = url.openConnection();
            try {
                return connection.getInputStream();
            }
            catch (IOException ex) {
                this.disconnect(connection);
                throw ex;
            }
        }
        return null;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private boolean exists(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        try {
            connection.setUseCaches(connection.getClass().getSimpleName().startsWith("JNLP"));
            if (connection instanceof HttpURLConnection) {
                HttpURLConnection httpConnection = (HttpURLConnection)connection;
                httpConnection.setRequestMethod("HEAD");
                int responseCode = httpConnection.getResponseCode();
                if (responseCode == 200) {
                    boolean bl = true;
                    return bl;
                }
                if (responseCode == 404) {
                    boolean bl = false;
                    return bl;
                }
            }
            boolean bl = connection.getContentLength() >= 0;
            return bl;
        }
        finally {
            this.disconnect(connection);
        }
    }

    private void disconnect(URLConnection connection) {
        if (connection instanceof HttpURLConnection) {
            HttpURLConnection httpConnection = (HttpURLConnection)connection;
            httpConnection.disconnect();
        }
    }

    private InputStream getFileResource(String config) throws Exception {
        File file = new File(config);
        debug.log("Trying file: %s", config);
        return !file.canRead() ? null : new FileInputStream(file);
    }

    private void loadResource(InputStream resource) throws Exception {
        this.properties.load(resource);
        this.resolvePropertyPlaceholders();
        if ("true".equalsIgnoreCase(this.getProperty(SET_SYSTEM_PROPERTIES))) {
            this.addToSystemProperties();
        }
    }

    private void resolvePropertyPlaceholders() {
        for (String name : this.properties.stringPropertyNames()) {
            String value = this.properties.getProperty(name);
            String resolved = SystemPropertyUtils.resolvePlaceholders(this.properties, value);
            if (resolved == null) continue;
            this.properties.put(name, resolved);
        }
    }

    private void addToSystemProperties() {
        debug.log("Adding resolved properties to System properties");
        for (String name : this.properties.stringPropertyNames()) {
            String value = this.properties.getProperty(name);
            System.setProperty(name, value);
        }
    }

    private List<String> getPaths() throws Exception {
        String path = this.getProperty(PATH);
        List<String> paths = path != null ? this.parsePathsProperty(path) : Collections.emptyList();
        debug.log("Nested archive paths: %s", this.paths);
        return paths;
    }

    private List<String> parsePathsProperty(String commaSeparatedPaths) {
        ArrayList<String> paths = new ArrayList<String>();
        for (String path : commaSeparatedPaths.split(",")) {
            path = (path = this.cleanupPath(path)).isEmpty() ? "/" : path;
            paths.add(path);
        }
        if (paths.isEmpty()) {
            paths.add("lib");
        }
        return paths;
    }

    private String cleanupPath(String path) {
        if ((path = path.trim()).startsWith("./")) {
            path = path.substring(2);
        }
        if (this.isArchive(path)) {
            return path;
        }
        if (path.endsWith("/*")) {
            return path.substring(0, path.length() - 1);
        }
        return !path.endsWith("/") && !path.equals(".") ? path + "/" : path;
    }

    @Override
    protected ClassLoader createClassLoader(Collection<URL> urls) throws Exception {
        String loaderClassName = this.getProperty("loader.classLoader");
        if (loaderClassName == null) {
            return super.createClassLoader(urls);
        }
        ClassLoader parent = this.getClass().getClassLoader();
        ClassLoader classLoader = new LaunchedClassLoader(false, urls.toArray(new URL[0]), parent);
        debug.log("Classpath for custom loader: %s", urls);
        classLoader = this.wrapWithCustomClassLoader(classLoader, loaderClassName);
        debug.log("Using custom class loader: %s", loaderClassName);
        return classLoader;
    }

    private ClassLoader wrapWithCustomClassLoader(ClassLoader parent, String loaderClassName) throws Exception {
        Instantiator instantiator = new Instantiator(parent, loaderClassName);
        ClassLoader loader = (ClassLoader)instantiator.declaredConstructor(ClassLoader.class).newInstance(parent);
        loader = loader != null ? loader : (ClassLoader)instantiator.declaredConstructor(URL[].class, ClassLoader.class).newInstance(NO_URLS, parent);
        ClassLoader classLoader = loader = loader != null ? loader : (ClassLoader)instantiator.constructWithoutParameters();
        if (loader != null) {
            return loader;
        }
        throw new IllegalStateException("Unable to create class loader for " + loaderClassName);
    }

    @Override
    protected Archive getArchive() {
        return null;
    }

    @Override
    protected String getMainClass() throws Exception {
        String mainClass = this.getProperty(MAIN, "Start-Class");
        if (mainClass == null) {
            throw new IllegalStateException("No '%s' or 'Start-Class' specified".formatted(MAIN));
        }
        return mainClass;
    }

    protected String[] getArgs(String ... args) throws Exception {
        String loaderArgs = this.getProperty(ARGS);
        return loaderArgs != null ? this.merge(loaderArgs.split("\\s+"), args) : args;
    }

    private String[] merge(String[] a1, String[] a2) {
        String[] result = new String[a1.length + a2.length];
        System.arraycopy(a1, 0, result, 0, a1.length);
        System.arraycopy(a2, 0, result, a1.length, a2.length);
        return result;
    }

    private String getProperty(String name) throws Exception {
        return this.getProperty(name, null, null);
    }

    private String getProperty(String name, String manifestKey) throws Exception {
        return this.getProperty(name, manifestKey, null);
    }

    private String getPropertyWithDefault(String name, String defaultValue) throws Exception {
        return this.getProperty(name, null, defaultValue);
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private String getProperty(String name, String manifestKey, String defaultValue) throws Exception {
        manifestKey = manifestKey != null ? manifestKey : PropertiesLauncher.toCamelCase(name.replace('.', '-'));
        String value = SystemPropertyUtils.getProperty(name);
        if (value != null) {
            return this.getResolvedProperty(name, manifestKey, value, "environment");
        }
        if (this.properties.containsKey(name)) {
            value = this.properties.getProperty(name);
            return this.getResolvedProperty(name, manifestKey, value, "properties");
        }
        if (this.homeDirectory != null) {
            try (ExplodedArchive explodedArchive = new ExplodedArchive(this.homeDirectory);){
                value = this.getManifestValue(explodedArchive, manifestKey);
                if (value != null) {
                    String string = this.getResolvedProperty(name, manifestKey, value, "home directory manifest");
                    return string;
                }
            }
            catch (IllegalStateException illegalStateException) {
                // empty catch block
            }
        }
        if ((value = this.getManifestValue(this.archive, manifestKey)) == null) return SystemPropertyUtils.resolvePlaceholders(this.properties, defaultValue);
        return this.getResolvedProperty(name, manifestKey, value, "manifest");
    }

    String getManifestValue(Archive archive, String manifestKey) throws Exception {
        Manifest manifest = archive.getManifest();
        return manifest != null ? manifest.getMainAttributes().getValue(manifestKey) : null;
    }

    private String getResolvedProperty(String name, String manifestKey, String value, String from) {
        value = SystemPropertyUtils.resolvePlaceholders(this.properties, value);
        String altName = manifestKey != null && !manifestKey.equals(name) ? "[%s] ".formatted(manifestKey) : "";
        debug.log("Property '%s'%s from %s: %s", name, altName, from, value);
        return value;
    }

    void close() throws Exception {
        if (this.archive != null) {
            this.archive.close();
        }
    }

    public static String toCamelCase(CharSequence string) {
        if (string == null) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        Matcher matcher = WORD_SEPARATOR.matcher(string);
        int pos = 0;
        while (matcher.find()) {
            result.append(PropertiesLauncher.capitalize(string.subSequence(pos, matcher.end()).toString()));
            pos = matcher.end();
        }
        result.append(PropertiesLauncher.capitalize(string.subSequence(pos, string.length()).toString()));
        return result.toString();
    }

    private static String capitalize(String str) {
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    @Override
    protected Set<URL> getClassPathUrls() throws Exception {
        LinkedHashSet<URL> urls = new LinkedHashSet<URL>();
        for (String path : this.getPaths()) {
            path = this.cleanupPath(this.handleUrl(path));
            urls.addAll(this.getClassPathUrlsForPath(path));
        }
        urls.addAll(this.getClassPathUrlsForRoot());
        debug.log("Using class path URLs %s", urls);
        return urls;
    }

    private Set<URL> getClassPathUrlsForPath(String path) throws Exception {
        Set<URL> nested;
        File file = !this.isAbsolutePath(path) ? new File(this.homeDirectory, path) : new File(path);
        LinkedHashSet<URL> urls = new LinkedHashSet<URL>();
        if (!"/".equals(path) && file.isDirectory()) {
            try (ExplodedArchive explodedArchive = new ExplodedArchive(file);){
                debug.log("Adding classpath entries from directory %s", file);
                urls.add(file.toURI().toURL());
                urls.addAll(explodedArchive.getClassPathUrls(this::isArchive));
            }
        }
        if (!file.getPath().contains(NESTED_ARCHIVE_SEPARATOR) && this.isArchive(file.getName())) {
            debug.log("Adding classpath entries from jar/zip archive %s", path);
            urls.add(file.toURI().toURL());
        }
        if (!(nested = this.getClassPathUrlsForNested(path)).isEmpty()) {
            debug.log("Adding classpath entries from nested %s", path);
            urls.addAll(nested);
        }
        return urls;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private Set<URL> getClassPathUrlsForNested(String path) throws Exception {
        int separatorIndex;
        File candidate;
        boolean isJustArchive = this.isArchive(path);
        if (!path.equals("/") && path.startsWith("/") || this.archive.isExploded() && this.archive.getRootDirectory().equals(this.homeDirectory)) {
            return Collections.emptySet();
        }
        File file = null;
        if (isJustArchive && (candidate = new File(this.homeDirectory, path)).exists()) {
            file = candidate;
            path = "";
        }
        if ((separatorIndex = path.indexOf(33)) != -1) {
            file = !path.startsWith(JAR_FILE_PREFIX) ? new File(this.homeDirectory, path.substring(0, separatorIndex)) : new File(path.substring(JAR_FILE_PREFIX.length(), separatorIndex));
            path = path.substring(separatorIndex + 1);
            path = this.stripLeadingSlashes(path);
        }
        if (path.equals("/") || path.equals("./") || path.equals(".")) {
            path = "";
        }
        Archive archive = file != null ? new JarFileArchive(file) : this.archive;
        try {
            LinkedHashSet<URL> urls = new LinkedHashSet<URL>(archive.getClassPathUrls(this.includeByPrefix(path)));
            if (!isJustArchive && file != null && path.isEmpty()) {
                urls.add(JarUrl.create(file));
            }
            LinkedHashSet<URL> linkedHashSet = urls;
            return linkedHashSet;
        }
        finally {
            if (archive != this.archive) {
                archive.close();
            }
        }
    }

    private Set<URL> getClassPathUrlsForRoot() throws IOException {
        debug.log("Adding classpath entries from root archive %s", this.archive);
        return this.archive.getClassPathUrls(JarLauncher::isLibraryFileOrClassesDirectory);
    }

    private Predicate<Archive.Entry> includeByPrefix(String prefix) {
        return entry -> entry.isDirectory() && entry.name().equals(prefix) || this.isArchive((Archive.Entry)entry) && entry.name().startsWith(prefix);
    }

    private boolean isArchive(Archive.Entry entry) {
        return this.isArchive(entry.name());
    }

    private boolean isArchive(String name) {
        return (name = name.toLowerCase(Locale.ENGLISH)).endsWith(".jar") || name.endsWith(".zip");
    }

    private boolean isAbsolutePath(String root) {
        return root.contains(":") || root.startsWith("/");
    }

    private String stripLeadingSlashes(String string) {
        while (string.startsWith("/")) {
            string = string.substring(1);
        }
        return string;
    }

    public static void main(String[] args) throws Exception {
        PropertiesLauncher launcher = new PropertiesLauncher();
        args = launcher.getArgs(args);
        launcher.launch(args);
    }

    private record Instantiator<T>(ClassLoader parent, Class<?> type) {
        Instantiator(ClassLoader parent, String className) throws ClassNotFoundException {
            this(parent, Class.forName(className, true, parent));
        }

        T constructWithoutParameters() throws Exception {
            return this.declaredConstructor(new Class[0]).newInstance(new Object[0]);
        }

        Using<T> declaredConstructor(Class<?> ... parameterTypes) {
            return new Using(this, parameterTypes);
        }

        private record Using<T>(Instantiator<T> instantiator, Class<?>[] parameterTypes) {
            T newInstance(Object ... initargs) throws Exception {
                try {
                    Constructor<?> constructor = this.instantiator.type().getDeclaredConstructor(this.parameterTypes);
                    constructor.setAccessible(true);
                    return (T)constructor.newInstance(initargs);
                }
                catch (NoSuchMethodException ex) {
                    return null;
                }
            }
        }
    }
}

