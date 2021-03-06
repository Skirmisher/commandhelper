package com.laytonsmith.core.extensions;

import com.laytonsmith.PureUtilities.ClassLoading.ClassDiscovery;
import com.laytonsmith.PureUtilities.ClassLoading.ClassDiscoveryCache;
import com.laytonsmith.PureUtilities.ClassLoading.ClassMirror.AnnotationMirror;
import com.laytonsmith.PureUtilities.ClassLoading.ClassMirror.ClassMirror;
import com.laytonsmith.PureUtilities.ClassLoading.ClassMirror.MethodMirror;
import com.laytonsmith.PureUtilities.ClassLoading.DynamicClassLoader;
import com.laytonsmith.PureUtilities.Common.OSUtils;
import com.laytonsmith.PureUtilities.Common.StackTraceUtils;
import com.laytonsmith.PureUtilities.Common.StringUtils;
import com.laytonsmith.PureUtilities.SimpleVersion;
import com.laytonsmith.abstraction.Implementation;
import com.laytonsmith.abstraction.StaticLayer;
import com.laytonsmith.annotations.api;
import com.laytonsmith.annotations.shutdown;
import com.laytonsmith.annotations.startup;
import com.laytonsmith.commandhelper.CommandHelperFileLocations;
import com.laytonsmith.core.CHLog;
import com.laytonsmith.core.LogLevel;
import com.laytonsmith.core.Prefs;
import com.laytonsmith.core.Static;
import com.laytonsmith.core.constructs.Target;
import com.laytonsmith.core.events.Event;
import com.laytonsmith.core.events.EventList;
import com.laytonsmith.core.functions.Function;
import com.laytonsmith.core.functions.FunctionList;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Server;

/**
 *
 * @author Layton
 */
public class ExtensionManager {
	private static final Map<URL, ExtensionTracker> extensions = new HashMap<>();
	private static final List<File> locations = new ArrayList<>();

	/**
	 * Process the given location for any jars. If the location is a jar, add it
	 * directly. If the location is a directory, look for jars in it.
	 *
	 * @param location file or directory
	 * @return
	 */
	private static List<File> getFiles(File location) {
		List<File> toProcess = new ArrayList<>();

		if (location.isDirectory()) {
			for (File f : location.listFiles()) {
				if (f.getName().endsWith(".jar")) {
					try {
						// Add the trimmed absolute path.
						toProcess.add(f.getCanonicalFile());
					} catch (IOException ex) {
						Logger.getLogger(ExtensionManager.class.getName()).log(
								Level.SEVERE, "Could not get exact path for "
								+ f.getAbsolutePath(), ex);
					}
				}
			}
		} else if (location.getName().endsWith(".jar")) {
			try {
				// Add the trimmed absolute path.
				toProcess.add(location.getCanonicalFile());
			} catch (IOException ex) {
				Logger.getLogger(ExtensionManager.class.getName()).log(
						Level.SEVERE, "Could not get exact path for "
						+ location.getAbsolutePath(), ex);
			}
		}

		return toProcess;
	}
	
	public static Map<URL, ExtensionTracker> getTrackers() {
		return Collections.unmodifiableMap(extensions);
	}

	public static void Cache(File extCache) {
		// We will only cache on Windows, as Linux doesn't natively lock
		// files that are in use. Windows prevents any modification, making
		// it harder for server owners on Windows to update the jars.
		boolean onWindows = (OSUtils.GetOS() == OSUtils.OS.WINDOWS);

		if (!onWindows) {
			return;
		}

		// Using System.out here instead of the logger as the logger doesn't
		// immediately print to the console.
		System.out.println("[CommandHelper] Caching extensions...");

		// Create the directory if it doesn't exist.
		extCache.mkdirs();

		// Try to delete any loose files in the cache dir, so that we
		// don't load stuff we aren't supposed to. This is in case the shutdown
		// cleanup wasn't successful on the last run.
		for (File f : extCache.listFiles()) {
			try {
				Files.delete(f.toPath());
			} catch (IOException ex) {
				Static.getLogger().log(Level.WARNING,
						"[CommandHelper] Could not delete loose file "
						+ f.getAbsolutePath() + ": " + ex.getMessage());
			}
		}

		// The cache, cd and dcl here will just be thrown away.
		// They are only used here for the purposes of discovering what a given 
		// jar has to offer.
		ClassDiscoveryCache cache = new ClassDiscoveryCache(
				CommandHelperFileLocations.getDefault().getCacheDirectory());
		DynamicClassLoader dcl = new DynamicClassLoader();
		ClassDiscovery cd = new ClassDiscovery();

		cd.setClassDiscoveryCache(cache);
		cd.addDiscoveryLocation(ClassDiscovery.GetClassContainer(ExtensionManager.class));
		cd.addDiscoveryLocation(ClassDiscovery.GetClassContainer(Server.class));

		//Look in the given locations for jars, add them to our class discovery.
		List<File> toProcess = new ArrayList<>();

		for (File location : locations) {
			toProcess.addAll(getFiles(location));
		}

		// Load the files into the discovery mechanism.
		for (File file : toProcess) {
			if (!file.canRead()) {
				continue;
			}

			URL jar;
			try {
				jar = file.toURI().toURL();
			} catch (MalformedURLException ex) {
				Logger.getLogger(ExtensionManager.class.getName()).log(Level.SEVERE, null, ex);
				continue;
			}

			dcl.addJar(jar);
			cd.addDiscoveryLocation(jar);
		}

		cd.setDefaultClassLoader(dcl);

		// Loop thru the found lifecycles, copy them to the cache using the name
		// given in the lifecycle. If more than one jar has the same internal
		// name, the filename will be given a number.
		Set<File> done = new HashSet<>();
		Map<String, Integer> namecount = new HashMap<>();

		// First, cache new lifecycle style extensions. They will be renamed to
		// use their internal name.
		for (ClassMirror<AbstractExtension> extmirror
				: cd.getClassesWithAnnotationThatExtend(
						MSExtension.class, AbstractExtension.class)) {
			AnnotationMirror plug = extmirror.getAnnotation(MSExtension.class);

			URL plugURL = extmirror.getContainer();

			// Get the internal name that this extension exposes.
			if (plugURL != null && plugURL.getPath().endsWith(".jar")) {
				File f;

				try {
					f = new File(plugURL.toURI());
				} catch (URISyntaxException ex) {
					Logger.getLogger(ExtensionManager.class.getName()).log(
							Level.SEVERE, null, ex);
					continue;
				}

				// Skip extensions that originate from commandhelpercore.
				if (plugURL.equals(ClassDiscovery.GetClassContainer(ExtensionManager.class))) {
					done.add(f);
				}

				// Skip files already processed.
				if (done.contains(f)) {
					CHLog.GetLogger().Log(CHLog.Tags.EXTENSIONS, LogLevel.WARNING,
							f.getAbsolutePath() + " contains more than one extension"
							+ " descriptor. Bug someone about it!", Target.UNKNOWN);

					continue;
				}

				done.add(f);

				String name = plug.getValue("value").toString();

				// Just in case we have two plugins with the same internal name,
				// lets track and rename them using a number scheme.
				if (namecount.containsKey(name.toLowerCase())) {
					int i = namecount.get(name.toLowerCase());
					name += "-" + i;
					namecount.put(name.toLowerCase(), i++);

					CHLog.GetLogger().Log(CHLog.Tags.EXTENSIONS, LogLevel.WARNING,
							f.getAbsolutePath() + " contains a duplicate internally"
							+ " named extension (" + name + "). Bug someone"
							+ " about it!", Target.UNKNOWN);
				} else {
					namecount.put(name.toLowerCase(), 1);
				}

				// Rename the jar to use the plugin's internal name and 
				// copy it into the cache.
				File newFile = new File(extCache, name.toLowerCase() + ".jar");

				try {
					Files.copy(f.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ex) {
					Logger.getLogger(ExtensionManager.class.getName()).log(
							Level.SEVERE, "Could not copy '" + f.getName()
							+ "' to cache: " + ex.getMessage());
				}
			}
		}

		Set<ClassMirror<?>> classes = cd.getClassesWithAnnotation(api.class);

		// Now process @api annotated extensions, ignoring ones already processed.
		for (ClassMirror klass : classes) {
			URL plugURL = klass.getContainer();

			if (plugURL != null && plugURL.getPath().endsWith(".jar")) {
				File f;

				try {
					f = new File(plugURL.toURI());
				} catch (URISyntaxException ex) {
					Logger.getLogger(ExtensionManager.class.getName()).log(
							Level.SEVERE, null, ex);
					continue;
				}

				// Skip files already processed.
				if (done.contains(f)) {
					continue;
				}

				// Copy the file if it's a valid extension.
				// No special processing needed.
				if (cd.doesClassExtend(klass, Event.class)
						|| cd.doesClassExtend(klass, Function.class)) {
					// We're processing it here instead of above, complain about it.
					CHLog.GetLogger().Log(CHLog.Tags.EXTENSIONS, LogLevel.WARNING,
							f.getAbsolutePath() + " is an old-style extension!"
							+ " Bug the author to update it to the new extension system!",
							Target.UNKNOWN);

					// Only process this file once.
					done.add(f);

					File newFile = new File(extCache, "oldstyle-" + f.getName());

					try {
						Files.copy(f.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
					} catch (IOException ex) {
						Logger.getLogger(ExtensionManager.class.getName()).log(
								Level.SEVERE, "Could not copy '" + f.getName()
								+ "' to cache: " + ex.getMessage());
					}
				}
			}
		}

		System.out.println("[CommandHelper] Extension caching complete.");

		// Shut down the original dcl to "unlock" the processed jars.
		// The cache and cd instances will just fall into oblivion.
		dcl.destroy();

		// Explicit call. Without this, jar files won't actually get unlocked on
		// Windows. Of course, this is hit and miss, but that's fine; we tried.
		System.gc();
	}

	/**
	 * Initializes the extension manager. This operation is not necessarily
	 * required, and must be guaranteed to not run more than once per
	 * ClassDiscovery object.
	 *
	 * @param cd the ClassDiscovery to use for loading files.
	 */
	public static void Initialize(ClassDiscovery cd) {
		extensions.clear();

		// Look in the extension folder for jars, add them to our class discover,
		// then initialize everything
		List<File> toProcess = new ArrayList<>();

		// Grab files from the cache if on Windows. Otherwise just load
		// directly from the stored locations.
		boolean onWindows = (OSUtils.GetOS() == OSUtils.OS.WINDOWS);

		if (onWindows) {
			toProcess.addAll(getFiles(CommandHelperFileLocations.getDefault().getExtensionCacheDirectory()));
		} else {
			for (File location : locations) {
				toProcess.addAll(getFiles(location));
			}
		}

		DynamicClassLoader dcl = new DynamicClassLoader();
		cd.setDefaultClassLoader(dcl);

		for (File f : toProcess) {
			if (f.getName().endsWith(".jar")) {
				try {
					//First, load it with our custom class loader
					URL jar = f.toURI().toURL();

					dcl.addJar(jar);
					cd.addDiscoveryLocation(jar);

					CHLog.GetLogger().Log(CHLog.Tags.EXTENSIONS, LogLevel.DEBUG, "Loaded " + f.getAbsolutePath(), Target.UNKNOWN);
				} catch (MalformedURLException ex) {
					Logger.getLogger(ExtensionManager.class.getName()).log(Level.SEVERE, null, ex);
				}
			}
		}

		// Grab all known lifecycle classes, and use them. If more than one 
		// lifecycle is found per URL, it's stored and used, but the first
		// one found defines the internal name.
		for (ClassMirror<AbstractExtension> extmirror : cd.getClassesWithAnnotationThatExtend(MSExtension.class, AbstractExtension.class)) {
			Extension ext;
			URL url = extmirror.getContainer();
			Class<AbstractExtension> extcls;
			
			if (extmirror.getModifiers().isAbstract()) {
				Logger.getLogger(ExtensionManager.class.getName()).log(Level.SEVERE,
						"Probably won't be able to instantiate " + extmirror.getClassName() 
								+ ": The class is marked as abstract! Will try anyway.");
			}
			
			try {
				extcls = extmirror.loadClass(dcl, true);
			} catch (NoClassDefFoundError ex) {
				ex.printStackTrace();
				continue;
			}
			
			try {
				ext = extcls.newInstance();
			} catch (InstantiationException | IllegalAccessException ex) {
				//Error, but skip this one, don't throw an exception ourselves, just log it.
				Logger.getLogger(ExtensionManager.class.getName()).log(Level.SEVERE,
						"Could not instantiate " + extcls.getName() + ": " + ex.getMessage());
				continue;
			}

			ExtensionTracker trk = extensions.get(url);

			if (trk == null) {
				trk = new ExtensionTracker(url, cd, dcl);

				extensions.put(url, trk);
			}
			
			// Grab the identifier for the first lifecycle we come across and
			// use it.
			if (trk.identifier == null) {
				trk.identifier = ext.getName();
				try {
					trk.version = ext.getVersion();
				} catch(AbstractMethodError ex){
					// getVersion() was added later. This is a temporary fix
					// to allow extension authors some time to update.
					// TODO: Remove this soon.
					trk.version = new SimpleVersion("0.0.0");
				}
			}

			trk.allExtensions.add(ext);
		}

		// Lets store info about the functions and events extensions have.
		// This will aide in gracefully unloading stuff later.
		Set<ClassMirror<?>> classes = cd.getClassesWithAnnotation(api.class);

		// Temp tracking for loading messages later on.
		List<String> events = new ArrayList<>();
		List<String> functions = new ArrayList<>();

		// Loop over the classes, instantiate and register functions and events,
		// and store the instances in their trackers.
		for (ClassMirror klass : classes) {
			URL url = klass.getContainer();

			if (cd.doesClassExtend(klass, Event.class)
					|| cd.doesClassExtend(klass, Function.class)) {
				Class c = klass.loadClass(dcl, true);

				String apiClass = (c.getEnclosingClass() != null
						? c.getEnclosingClass().getName().split("\\.")[c.getEnclosingClass().getName().split("\\.").length - 1]
						: "<global>");

				ExtensionTracker trk = extensions.get(url);

				if (trk == null) {
					trk = new ExtensionTracker(url, cd, dcl);

					extensions.put(url, trk);
				}

				// Instantiate, register and store.
				try {
					if (Event.class.isAssignableFrom(c)) {
						Class<Event> cls = (Class<Event>) c;
						
						if (klass.getModifiers().isAbstract()) {
							// Abstract? Looks like they accidently @api'd
							// a cheater class. We can't be sure that it is fully
							// defined, so complain to the console.
							CHLog.GetLogger().Log(CHLog.Tags.EXTENSIONS, LogLevel.ERROR, 
								"Class " + c.getName() + " in " + url + " is"
								+ " marked as an event but is also abstract."
								+ " Bugs might occur! Bug someone about this!",
								Target.UNKNOWN);
						}
						
						Event e = cls.newInstance();
						events.add(e.getName());

						EventList.registerEvent(e, apiClass);

						trk.events.add(e);
					} else if (Function.class.isAssignableFrom(c)) {
						Class<Function> cls = (Class<Function>) c;
						
						if (klass.getModifiers().isAbstract()) {
							// Abstract? Looks like they accidently @api'd
							// a cheater class. We can't be sure that it is fully
							// defined, so complain to the console.
							CHLog.GetLogger().Log(CHLog.Tags.EXTENSIONS, LogLevel.ERROR, 
								"Class " + c.getName() + " in " + url + " is"
								+ " marked as a function but is also abstract."
								+ " Bugs might occur! Bug someone about this!",
								Target.UNKNOWN);
						}
						
						Function f = cls.newInstance();
						functions.add(f.getName());

						FunctionList.registerFunction(f, apiClass);

						trk.functions.add(f);
					}
				} catch (InstantiationException ex) {
					Logger.getLogger(ExtensionManager.class.getName()).log(Level.SEVERE, ex.getMessage(), ex);
				} catch (IllegalAccessException ex) {
					Logger.getLogger(ExtensionManager.class.getName()).log(Level.SEVERE, null, ex);
				}
			}
		}

		// Lets print out the details to the console, if we are in debug mode.
		if (Prefs.DebugMode()) {
			Collections.sort(events);
			String eventString = StringUtils.Join(events, ", ", ", and ", " and ");
			Collections.sort(functions);
			String functionString = StringUtils.Join(functions, ", ", ", and ", " and ");

			System.out.println(Implementation.GetServerType().getBranding()
					+ ": Loaded the following functions: " + functionString.trim());
			System.out.println(Implementation.GetServerType().getBranding()
					+ ": Loaded " + functions.size() + " function" + (functions.size() == 1 ? "." : "s."));
			System.out.println(Implementation.GetServerType().getBranding()
					+ ": Loaded the following events: " + eventString.trim());
			System.out.println(Implementation.GetServerType().getBranding()
					+ ": Loaded " + events.size() + " event" + (events.size() == 1 ? "." : "s."));
		}
	}

	/**
	 * To be run when we are shutting everything down.
	 */
	public static void Cleanup() {
		// Shutdown and release all the extensions
		for (ExtensionTracker trk : extensions.values()) {
			trk.shutdownTracker();
		}

		extensions.clear();

		// Clean up the loaders and discovery instances.
		ClassDiscovery.getDefaultInstance().invalidateCaches();
		ClassLoader loader = ClassDiscovery.getDefaultInstance().getDefaultClassLoader();

		if (loader instanceof DynamicClassLoader) {
			DynamicClassLoader dcl = (DynamicClassLoader) loader;
			dcl.destroy();
		}

		// Explicit call. Without this, jar files won't actually get unlocked on
		// Windows. Of course, this is hit and miss, but that's fine; we tried.
		System.gc();

		File cacheDir = CommandHelperFileLocations.getDefault().getExtensionCacheDirectory();

		if (!cacheDir.exists() || !cacheDir.isDirectory()) {
			return;
		}

		// Try to delete any loose files in the cache dir.
		for (File f : cacheDir.listFiles()) {
			try {
				Files.delete(f.toPath());
			} catch (IOException ex) {
				System.out.println("[CommandHelper] Could not delete loose file "
						+ f.getAbsolutePath() + ": " + ex.getMessage());
			}
		}
	}

	/**
	 * This should be run each time the "startup" of the runtime occurs. It
	 * registers its own shutdown hook.
	 */
	@SuppressWarnings("deprecation")
	public static void Startup() {
		for (ExtensionTracker trk : extensions.values()) {
			for (Extension ext : trk.getExtensions()) {
				try {
					ext.onStartup();
				} catch (Throwable e) {
					Logger log = Logger.getLogger(ExtensionManager.class.getName());
					log.log(Level.SEVERE, ext.getClass().getName()
							+ "'s onStartup caused an exception:");
					log.log(Level.SEVERE, StackTraceUtils.GetStacktrace(e));
				}
			}
		}

		// Deprecated. Soon to be removed!
		for (MethodMirror mm : ClassDiscovery.getDefaultInstance().getMethodsWithAnnotation(startup.class)) {
			if (!mm.getParams().isEmpty()) {
				//Error, but skip this one, don't throw an exception ourselves, just log it.
				Logger.getLogger(ExtensionManager.class.getName()).log(Level.SEVERE,
						"Method annotated with @" + startup.class.getSimpleName()
						+ " takes parameters; it should not.");
			} else if (!mm.getModifiers().isStatic()) {
				CHLog.GetLogger().Log(CHLog.Tags.EXTENSIONS, LogLevel.ERROR,
						"Method " + mm.getDeclaringClass() + "#" + mm.getName()
						+ " is not static, but it should be.", Target.UNKNOWN);
			} else {
				try {
					Method m = mm.loadMethod(ClassDiscovery.getDefaultInstance().getDefaultClassLoader(), true);
					m.setAccessible(true);
					m.invoke(null, (Object[]) null);
				} catch (Throwable e) {
					CHLog.GetLogger().Log(CHLog.Tags.EXTENSIONS, LogLevel.ERROR,
							"Method " + mm.getDeclaringClass() + "#"
							+ mm.getName() + " threw an exception during runtime:\n"
							+ StackTraceUtils.GetStacktrace(e), Target.UNKNOWN);
				}
			}
		}

		StaticLayer.GetConvertor().addShutdownHook(new Runnable() {

			@Override
			public void run() {
				for (ExtensionTracker trk : extensions.values()) {
					for (Extension ext : trk.getExtensions()) {
						try {
							ext.onShutdown();
						} catch (Throwable e) {
							Logger log = Logger.getLogger(ExtensionManager.class.getName());
							log.log(Level.SEVERE, ext.getClass().getName()
									+ "'s onStartup caused an exception:");
							log.log(Level.SEVERE, StackTraceUtils.GetStacktrace(e));
						}
					}
				}
				
				// Deprecated. Soon to be removed!
				for (MethodMirror mm : ClassDiscovery.getDefaultInstance().getMethodsWithAnnotation(shutdown.class)) {
					if (!mm.getParams().isEmpty()) {
						//Error, but skip this one, don't throw an exception ourselves, just log it.
						CHLog.GetLogger().Log(CHLog.Tags.EXTENSIONS, LogLevel.ERROR,
								"Method annotated with @" + shutdown.class.getSimpleName()
								+ " takes parameters; it should not. (Found in "
								+ mm.getDeclaringClass() + "#" + mm.getName() + ")", Target.UNKNOWN);
					} else {
						try {
							Method m = mm.loadMethod(ClassDiscovery.getDefaultInstance().getDefaultClassLoader(), true);
							m.setAccessible(true);
							m.invoke(null);
						} catch (Throwable e) {
							CHLog.GetLogger().Log(CHLog.Tags.EXTENSIONS, LogLevel.ERROR, "Method " + mm.getDeclaringClass() + "#" + mm.getName() + " threw an exception during runtime:\n" + StackTraceUtils.GetStacktrace(e), Target.UNKNOWN);
						}
					}
				}
			}
		});
	}

	public static void PreReloadAliases(boolean reloadGlobals, boolean reloadTimeouts,
			boolean reloadExecutionQueue, boolean reloadPersistenceConfig,
			boolean reloadPreferences, boolean reloadProfiler,
			boolean reloadScripts, boolean reloadExtensions) {
		for (ExtensionTracker trk : extensions.values()) {
			for (Extension ext: trk.getExtensions()) {
				try {
					ext.onPreReloadAliases(reloadGlobals,
						reloadTimeouts, reloadExecutionQueue,
						reloadPersistenceConfig, reloadPreferences,
						reloadProfiler, reloadScripts, reloadExtensions);
				} catch (Throwable e) {
					Logger log = Logger.getLogger(ExtensionManager.class.getName());
					log.log(Level.SEVERE, ext.getClass().getName()
							+ "'s onPreReloadAliases caused an exception:");
					log.log(Level.SEVERE, StackTraceUtils.GetStacktrace(e));
				}
			}
		}
	}

	public static void PostReloadAliases() {
		for (ExtensionTracker trk : extensions.values()) {
			for (Extension ext: trk.getExtensions()) {
				try {
					ext.onPostReloadAliases();
				} catch (Throwable e) {
					Logger log = Logger.getLogger(ExtensionManager.class.getName());
					log.log(Level.SEVERE, ext.getClass().getName()
							+ "'s onPreReloadAliases caused an exception:");
					log.log(Level.SEVERE, StackTraceUtils.GetStacktrace(e));
				}
			}
		}
	}

	public static void AddDiscoveryLocation(File file) {
		try {
			locations.add(file.getCanonicalFile());
		} catch (IOException ex) {
			Logger.getLogger(ExtensionManager.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
}
