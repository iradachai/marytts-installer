package marytts.tools.newinstall;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import marytts.tools.newinstall.enums.LogLevel;
import marytts.tools.newinstall.enums.Status;
import marytts.tools.newinstall.objects.Component;
import marytts.tools.newinstall.objects.LangComponent;
import marytts.tools.newinstall.objects.VoiceComponent;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.ivy.Ivy;
import org.apache.ivy.core.IvyPatternHelper;
import org.apache.ivy.core.install.InstallOptions;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.descriptor.DependencyArtifactDescriptor;
import org.apache.ivy.core.module.descriptor.DependencyDescriptor;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.retrieve.RetrieveOptions;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorParser;
import org.apache.ivy.plugins.resolver.DependencyResolver;
import org.apache.ivy.plugins.resolver.RepositoryResolver;
import org.apache.ivy.util.DefaultMessageLogger;
import org.apache.ivy.util.filter.Filter;
import org.apache.log4j.Logger;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import com.google.gson.Gson;

/**
 * Main class of the component installer. Both holds the main method and holds data and functionality methods on the component
 * data
 * 
 * @author Jonathan, Ingmar
 * 
 */
public class Installer {

	// Ivy instance, used for installation and resolving purposes
	private Ivy ivy;

	// Java representation of the ivysettings.xml file that holds information about repository structure and location
	private IvySettings ivySettings;

	// Options passed on to the resolve/install methods of the Ivy object
	private ResolveOptions resolveOptions;
	private InstallOptions installOptions;

	private UnzippingArtifactTypeFilter jarFilter;

	// holds all currently available components
	private Set<Component> resources;

	// the instance of the command line interface which is created once the installer is started
	private InstallerCLI cli;

	// holds the directory where marytts should be (preferably: is) installed. This location will be used to put downloaded and
	// installed components in
	private String maryBasePath;

	// holds the logLevel currently used. Used to set Ivy logging once Ivy is started.
	private LogLevel logLevel;

	static Logger logger = Logger.getLogger(marytts.tools.newinstall.Installer.class.getName());

	public Installer(String[] args) {

		logger.debug("Loading installer.");
		this.resources = Sets.newTreeSet();
		this.jarFilter = new UnzippingArtifactTypeFilter();
		// default value for logging. may be overwritten by InstallerCLI
		this.logLevel = LogLevel.info;
		this.cli = new InstallerCLI(args, this);

		// test if user has specified mary path on command line. If not, determine directory Installer is run from
		if (this.maryBasePath == null) {
			// this method will also loadIvySettings(), loadIvy() and parseIvyResources()
			setMaryBase();
		}
		logger.debug("Set mary base path to: " + this.maryBasePath);

		// setup ivy

		// try {
		// // loads ivy settings from resources ivysettings.xml file
		// loadIvySettings();
		//
		// // creating a new ivy instance as well as sets necessary options
		// loadIvy();
		//
		// logger.debug("Starting ivy resource parse");
		// // parses component descriptors, creates Component objects from them and stores them in this.resources
		// parseIvyResources();

		// once the resources are parsed, Installer passes the workflow on to the command line interface which evaluates
		// parameters that have been passed on to the Installer
		this.cli.mainEvalCommandLine();

		// } catch (IOException ioe) {
		// logger.error("Could not access settings file: " + ioe.getMessage());
		// } catch (ParseException pe) {
		// logger.error("Could not access settings file: " + pe.getMessage());
		// }
	}

	/**
	 * creates an Ivy instance and sets necessary options
	 */
	public void loadIvy() {
		logger.info("Starting Ivy ...");
		this.ivy = Ivy.newInstance(this.ivySettings);
		logger.debug("Setting log level to " + this.logLevel.toString());
		DefaultMessageLogger defaultLogger = new DefaultMessageLogger(this.logLevel.ordinal());
		defaultLogger.setShowProgress(true);
		this.ivy.getLoggerEngine().setDefaultLogger(defaultLogger);

		this.resolveOptions = new ResolveOptions();
		this.installOptions = new InstallOptions().setOverwrite(true).setTransitive(true);
	}

	public void loadIvySettings() throws ParseException, IOException {
		this.ivySettings = new IvySettings();
		this.ivySettings.setVariable("mary.base", this.maryBasePath);
		logger.debug("Loading ivysettings.xml ...");
		this.ivySettings.load(Resources.getResource("ivysettings.xml"));

	}

	protected void setLogLevel(LogLevel logLevel) {

		this.logLevel = logLevel;
	}

	/**
	 * method to set the maryBasePath variable. Is only called if user didn't manually set a path on the command line and thus
	 * uses instead the location where the Installer.jar is run from
	 */
	private void setMaryBase() {
		logger.debug("Setting mary base directory ...");
		File maryBase = null;
		// fall back to location of this class/jar
		// from http://stackoverflow.com/a/320595
		URL location = Installer.class.getProtectionDomain().getCodeSource().getLocation();
		try {
			maryBase = new File(location.toURI().getPath());
			logger.debug("Setting mary base directory - Trying to use directory Installer is run from - " + maryBase);
		} catch (URISyntaxException use) {
			logger.error("Setting mary base directory - Could not parse " + location + ": " + use.getMessage() + "\n");
		}
		setMaryBase(maryBase);

		// try {
		// this.installer.loadIvySettings();
		// this.installer.loadIvy();
		// } catch (IOException ioe) {
		// logger.error("Could not access settings file: " + ioe.getMessage());
		// } catch (ParseException pe) {
		// logger.error("Could not access settings file: " + pe.getMessage());
		// }
		// this.installer.parseIvyResources();
	}

	/**
	 * sets a new file path for the marytts base directory.
	 * 
	 * @param maryBase
	 * @return true if mary path was successfully set, false otherwise
	 */
	public boolean setMaryBase(File maryBase) {
		boolean isSuccessful;
		try {
			maryBase = maryBase.getCanonicalFile();
			isSuccessful = true;
		} catch (IOException ioe) {
			logger.error("Setting mary base directory - Could not determine path to directory " + maryBase + ": " + ioe + "\n");
			isSuccessful = false;
		}
		// if this is running from the jar file, back off to directory containing it
		if (maryBase.isFile()) {
			logger.debug("Setting mary base directory - Installer is running from jar. Creating directory for setting mary base path");
			maryBase = maryBase.getParentFile();
			isSuccessful = true;
		}
		// create directory (with parents, if required)
		try {
			FileUtils.forceMkdir(maryBase);
			isSuccessful = true;
		} catch (IOException ioe) {
			logger.error(ioe.getMessage());
			isSuccessful = false;
		}
		try {
			this.maryBasePath = maryBase.getCanonicalPath();
			isSuccessful = true;
		} catch (IOException ioe) {
			logger.error("Setting mary base directory - Could not determine path to directory " + maryBase + ": " + ioe + "\n");
			isSuccessful = false;
		}

		if (isSuccessful) {

			logger.debug("(Re)loading Ivy, IvySettings and (re)parsing IvyResources ...");
			reloadIvy();
		}

		return isSuccessful;
	}

	public void reloadIvy() {
		try {
			loadIvySettings();
			loadIvy();
			parseIvyResources();
		} catch (IOException ioe) {
			logger.error("Could not access settings file: " + ioe.getMessage());
		} catch (ParseException pe) {
			logger.error("Could not access settings file: " + pe.getMessage());
		}
	}

	/**
	 * @return the maryBasePath
	 */
	public String getMaryBasePath() {
		return this.maryBasePath;
	}

	public void uninstall(Component component) {

		logger.info("Ivy is uninstalling component " + component.getName());

		String artifactName = component.getArtifactName();
		// URL resource = Resources.getResource(null, "META-INF/services/marytts.config.MaryConfig");

		File artifactFile = new File(this.maryBasePath + "/lib/" + artifactName);
		if (artifactFile.exists()) {
			artifactFile.delete();
			logger.info(component.getName() + " has successfully been uninstalled");
		} else {
			logger.info(component.getName() + " is not installed and thus can not be uninstalled");
		}

		try {
			// this is the string without "voice" prefix. This is a workaround to fetching the component name from the config file
			// which is located in the jar.
			String pureCompName = component.getModuleDescriptor().getExtraAttribute("name");
			if (component instanceof VoiceComponent) {
				if (((VoiceComponent) component).getType().equals("unit selection")) {
					FileUtils.deleteDirectory(new File(this.maryBasePath + "/lib/voices/" + pureCompName));
				}
			}
		} catch (IOException e) {
			logger.warn("Failure while removing data for unit selection voice " + component.getName());
		}
	}

	/**
	 * Installs given component using the ivy instance of this class
	 * 
	 * @param component
	 * @throws ParseException
	 * @throws IOException
	 */
	public void install(Component component) throws ParseException, IOException {

		logger.info("Ivy is installing component " + component.getName() + " and resolving its dependencies ...");
		// ResolveReport resolveAllDependencies = this.ivy.resolve(component.getModuleDescriptor(), this.resolveOptions);
		ResolveReport resolveReport = this.ivy.resolve(component.getModuleDescriptor(), this.resolveOptions);
		// RetrieveReport retrieveReport = this.ivy.retrieve(component.getModuleDescriptor().getModuleRevisionId(),
		// new RetrieveOptions());
		RepositoryResolver resolver = (RepositoryResolver) this.ivy.getSettings().getResolver("installed");
		String ivyPattern = (String) resolver.getIvyPatterns().get(0);
		String artifactPattern = (String) resolver.getArtifactPatterns().get(0);
		RetrieveOptions retrieveOptions = new RetrieveOptions();
		retrieveOptions.setDestIvyPattern(ivyPattern).setDestArtifactPattern(artifactPattern);

		// do not install zip, but leave them in download and unpack them from there
		retrieveOptions.setArtifactFilter(this.jarFilter);

		this.ivy.retrieve(component.getModuleDescriptor().getModuleRevisionId(), retrieveOptions);
		logger.debug("The ModulDescriptor for the selected component is: " + component.getModuleDescriptor());

		logger.info("HERE SHOULD BE LOGGING FOR THE INSTALLATION AND RESOLUTION OF COMPONENTS");

		bootstrapMaryInstallation();
	}

	private void bootstrapMaryInstallation() {

		FileOutputStream outputStream;
		URL resource;

		try {
			if (new File(this.maryBasePath + "/LICENSE.txt").exists()) {
				logger.warn("Not overwriting the already present resource LICENSE.txt");
			} else {
				resource = Resources.getResource("LICENSE.txt");
				outputStream = new FileOutputStream(this.maryBasePath + "/LICENSE.txt");
				Resources.copy(resource, outputStream);
			}

			File binDir = new File(this.maryBasePath + "/bin");
			if (binDir.exists()) {
				logger.warn("Not overwriting the already present bin directory");
			} else {
				FileUtils.forceMkdir(binDir);

				resource = Resources.getResource("marytts-server");

				File maryttsServerFile = new File(binDir + "/marytts-server");
				outputStream = new FileOutputStream(maryttsServerFile);
				Resources.copy(resource, outputStream);
				maryttsServerFile.setExecutable(true);

				resource = Resources.getResource("marytts-server.bat");
				outputStream = new FileOutputStream(binDir + "/marytts-server.bat");
				Resources.copy(resource, outputStream);
			}

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	/**
	 * helper method to get information about dependencies of component prior to its resolution
	 * 
	 * @param component
	 * @return
	 */
	public List<String> retrieveDependencies(Component component) {

		List<String> toReturn = new ArrayList<String>();
		DependencyDescriptor[] dependencies = component.getModuleDescriptor().getDependencies();
		for (DependencyDescriptor oneDep : dependencies) {
			for (DependencyArtifactDescriptor oneDepArtifact : oneDep.getAllDependencyArtifacts()) {
				String depArtName = oneDepArtifact.getName();
				String depArtClassifier = oneDepArtifact.getExtraAttribute("classifier");
				if (depArtClassifier != null) {
					depArtName = depArtName.concat("-").concat(depArtClassifier);
				}
				toReturn.add(depArtName);
			}
		}
		return toReturn;
	}

	/**
	 * @param componentName
	 * @return
	 */
	public long getSizeOfComponentByName(String componentName) {

		for (Component oneComponent : this.resources) {
			if (componentName.equalsIgnoreCase(oneComponent.getName())) {
				return oneComponent.getSize();
			}
		}
		return 0L;
	}

	/**
	 * Parse list of voice descriptors from JSON array in resource. The resource is generated at compile time by the <a
	 * href="http://numberfour.github.io/file-list-maven-plugin/list-mojo.html">file-list-maven-plugin</a>.
	 * 
	 * @return List of voice descriptor resources
	 * @throws IOException
	 */
	public ArrayList<URL> readComponentDescriptorList() throws IOException {
		URL componentListResource = Resources.getResource("component-list.json");
		logger.debug("Reading component descriptor list component-list.json from resources");
		String componentListJson = Resources.toString(componentListResource, Charsets.UTF_8);
		String[] componentDescriptors = new Gson().fromJson(componentListJson, String[].class);
		ArrayList<URL> resourceList = Lists.newArrayListWithCapacity(componentDescriptors.length);
		for (String componentDescriptor : componentDescriptors) {
			URL resource = Resources.getResource(componentDescriptor);
			resourceList.add(resource);
		}
		return resourceList;
	}

	/**
	 * retrieves the voice component names from the {@link #readComponentDescriptorList()} and creates {@link Component} objects.<br>
	 * Those Components then are added to the list holding all Components and the {{@link #storeAttributeValues(Component)} method
	 * takes care of storing possible attribute values in a HashMap<br>
	 * TODO remove repeated code
	 * 
	 * @throws ParseException
	 * @throws IOException
	 */
	public void parseIvyResources() {

		try {
			List<URL> resourcesURLList = readComponentDescriptorList();

			// checks maryBase/download for the possible case of manually added components and adds them to the set of available
			// components as well
			resourcesURLList = addSupplComponents(resourcesURLList);

			// as this method can be used to reparse the components, clear the existing ones first
			this.resources.clear();

			for (URL oneResource : resourcesURLList) {
				String oneFileName = new File(oneResource.toString()).getName();
				ModuleDescriptor descriptor = XmlModuleDescriptorParser.getInstance().parseDescriptor(this.ivySettings,
						oneResource, true);
				logger.debug("Parsing " + oneFileName + " into moduleDescriptor: " + descriptor.toString());
				Component oneComponent = null;
				if (oneFileName.startsWith("voice")) {
					oneComponent = new VoiceComponent(descriptor);
				} else if (oneFileName.startsWith("marytts-lang")) {
					oneComponent = new LangComponent(descriptor);
				} else if (oneFileName.startsWith("marytts")) {
					// this last is a workaround to make sure that ivy's side effect xmls are not parsed as
					// components (-> resolved-marytts...)
					oneComponent = new Component(descriptor);
				} else {
					// in the above mentioned side-effect cases, a non valid module descriptor was generated. We take the file
					// names as a hack for sorting them out. Not pretty, but works.
					continue;
				}

				String artifactName = oneComponent.getArtifactName();
				logger.debug("The artifact name is: " + artifactName + " and has the following resource status: "
						+ getResourceStatus(artifactName));
				oneComponent.setStatus(getResourceStatus(artifactName));
				boolean addStatus = this.resources.add(oneComponent);
				if (!addStatus) {
					logger.debug(oneComponent.getName() + " was not added.");
				}
				logger.debug((oneComponent.getClass().getSimpleName().equals("VoiceComponent") ? "VoiceComponent " : "Component ")
						+ oneComponent.getName() + " added to resource list.");
			}
		} catch (IOException ioe) {
			logger.error("Problem reading in file: " + ioe.getMessage());
		} catch (ParseException pe) {
			logger.error("Problem parsing component file: " + pe.getMessage());
		}
	}

	private List<URL> addSupplComponents(List<URL> resourcesURLList) throws ParseException, IOException {

		File downloadDir = new File(this.maryBasePath + "/download");
		FilenameFilter filenameFilter = new FilenameFilter() {

			@Override
			public boolean accept(File dir, String name) {

				// TODO this filter is messy! only use as workaround till clean way is found for filtering our moduleDescriptors
				// from the ones that are automatically created
				if (name.endsWith(".xml") && name.contains("descriptor") && !name.startsWith("resolved")) {
					return true;
				}
				return false;
			}
		};

		XmlModuleDescriptorParser xmlParser = XmlModuleDescriptorParser.getInstance();
		ModuleDescriptor moduleDescriptor;
		File[] moduleDescriptorsInCache = downloadDir.listFiles(filenameFilter);

		if (moduleDescriptorsInCache != null) {
			for (final File oneXMLFile : moduleDescriptorsInCache) {
				URL xmlURL = oneXMLFile.toURI().toURL();
				logger.debug(xmlURL);

				try {
					moduleDescriptor = xmlParser.parseDescriptor(this.ivySettings, xmlURL, true);
				} catch (ParseException pe) {
					logger.debug(pe.getMessage());
					logger.debug(xmlURL + " does not specify a parsable resource. Skipping it ...");
					continue;
				}

				if (moduleDescriptor != null) {
					// Artifact[] allArtifacts = moduleDescriptor.getAllArtifacts();
					DependencyDescriptor[] dependencies = moduleDescriptor.getDependencies();
					if (dependencies != null) {
						resourcesURLList.add(xmlURL);
					}
				}
			}
		}
		return resourcesURLList;
	}

	public Status getResourceStatus(String componentName) {

		if (new File(this.maryBasePath + "/lib/" + componentName).exists()) {
			return Status.INSTALLED;
		}
		if (new File(this.maryBasePath + "/download/" + componentName).exists()) {
			return Status.DOWNLOADED;
		}
		return Status.AVAILABLE;
	}

	public void updateResourceStatuses() {

		logger.debug("Updating all resource statuses ...");
		for (Component oneComponent : this.resources) {
			String artifactName = oneComponent.getArtifactName();
			oneComponent.setStatus(getResourceStatus(artifactName));
		}
		logger.debug("Updating all resource statuses ... done");
	}

	/**
	 * filters the available components by one or many attribute-value pairs. Iterates over list of components and removes those
	 * that match the given attribute-value pair. if all attributeValues are null, the method simply returns all components.
	 * 
	 * @param locale
	 * @param type
	 * @param gender
	 * @param status
	 * @param name
	 * @return component list
	 */
	public List<Component> getAvailableComponents(String locale, String type, String gender, String status, String name,
			boolean voiceOnly) {

		List<Component> resourcesToBeFiltered = new ArrayList<Component>(this.resources);
		logger.debug("Fetching component list with the following parameters: "
				+ ((locale != null) ? ("locale=" + locale + " ") : "") + ((type != null) ? ("type=" + type + " ") : "")
				+ ((gender != null) ? ("gender=" + gender + " ") : "") + ((status != null) ? ("status=" + status + " ") : "")
				+ ((name != null) ? ("name=" + name + " ") : ""));

		// stores the size of the voice component list before filtering.
		int sizeBefore = resourcesToBeFiltered.size();
		logger.debug("Resource list size before filtering: " + sizeBefore);

		// in order to modify the list while iterating over it, an iterator is needed to call the Iterator.remove() method.
		Iterator<Component> it;

		if (resourcesToBeFiltered.isEmpty()) {
			logger.warn("List is empty!");
			return resourcesToBeFiltered;
		}

		if (locale != null && !locale.equals("all")) {
			for (it = resourcesToBeFiltered.iterator(); it.hasNext();) {
				Component oneComponent = it.next();
				if (!(oneComponent instanceof VoiceComponent || oneComponent instanceof LangComponent)) {
					logger.debug("Removed " + oneComponent + " as it is not a VoiceComponent or LangComponent");
					it.remove();
					continue;
				}
				if (oneComponent instanceof VoiceComponent) {
					VoiceComponent oneVoiceComponent = (VoiceComponent) oneComponent;
					if (!oneVoiceComponent.getLocale().toString().equalsIgnoreCase(locale)) {
						it.remove();
					}
				}
				if (oneComponent instanceof LangComponent) {
					LangComponent oneLangComponent = (LangComponent) oneComponent;
					if (!oneLangComponent.getLocale().toString().equalsIgnoreCase(locale)) {
						it.remove();
					}
				}
			}
		}
		if (type != null && !type.equals("all")) {
			for (it = resourcesToBeFiltered.iterator(); it.hasNext();) {
				Component oneComponent = it.next();
				if (!(oneComponent instanceof VoiceComponent)) {
					logger.debug("Removed " + oneComponent + " as it is not a VoiceComponent");
					it.remove();
					continue;
				}
				VoiceComponent oneVoiceComponent = (VoiceComponent) oneComponent;
				if (!oneVoiceComponent.getType().equalsIgnoreCase(type)) {
					it.remove();
				}
			}
		}
		if (gender != null && !gender.equals("all")) {
			for (it = resourcesToBeFiltered.iterator(); it.hasNext();) {
				Component oneComponent = it.next();
				if (!(oneComponent instanceof VoiceComponent)) {
					logger.debug("Removed " + oneComponent + " as it is not a VoiceComponent");
					it.remove();
					continue;
				}
				VoiceComponent oneVoiceComponent = (VoiceComponent) oneComponent;
				if (!oneVoiceComponent.getGender().equalsIgnoreCase(gender)) {
					it.remove();
				}
			}
		}
		if (status != null && !status.equals("all")) {
			for (it = resourcesToBeFiltered.iterator(); it.hasNext();) {
				Component oneComponent = it.next();
				if (!oneComponent.getStatus().toString().equalsIgnoreCase(status)) {
					it.remove();
				}
			}
		}
		if (name != null && !name.equals("all")) {
			for (it = resourcesToBeFiltered.iterator(); it.hasNext();) {
				Component oneComponent = it.next();
				if (!oneComponent.getName().equalsIgnoreCase(name)) {
					it.remove();
				}
			}
		}
		if (voiceOnly) {
			logger.debug("filtering by component type=" + (voiceOnly ? "voice " : " ") + "component");
			for (it = resourcesToBeFiltered.iterator(); it.hasNext();) {
				Component oneComponent = it.next();
				if (!(oneComponent instanceof VoiceComponent)) {
					it.remove();
				}
			}
		}

		return resourcesToBeFiltered;
	}

	/**
	 * checks if component list contains a {@link Component} with the name equal to the one passed along to this method.
	 * 
	 * @param nameValue
	 *            the value of the name to be searched for
	 * @return true if nameValue was found, false otherwise
	 */
	public boolean isNamePresent(String nameValue) {

		for (Component oneComponent : this.resources) {
			if (oneComponent.getName().equalsIgnoreCase(nameValue)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 
	 * Installer Main Method<br>
	 * <b>Note:</b> must currently run with -Dmary.base=/path/to/marytts
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		Installer installer = new Installer(args);
	}

	public class UnzippingArtifactTypeFilter implements Filter {

		@Override
		public boolean accept(Object object) {
			if (!(object instanceof Artifact)) {
				return false;
			}
			Artifact artifact = (Artifact) object;
			if (artifact.getType().equals("jar")) {
				return true;
			}
			if (artifact.getType().equals("zip") && artifact.getExtraAttribute("classifier").equals("data")) {
				unzip(artifact);
			}
			return false;
		}

		private void unzip(Artifact artifact) {

			RepositoryResolver resolver = (RepositoryResolver) Installer.this.ivy.getSettings().getResolver("installed");
			String destArtifactPattern = (String) resolver.getArtifactPatterns().get(0);

			String resolvedArtifactPattern = Installer.this.ivySettings.getDefaultCacheArtifactPattern();
			String resolvedFileName = IvyPatternHelper.substitute(resolvedArtifactPattern, artifact);
			File resolvedFile = new File(Installer.this.ivySettings.getDefaultResolutionCacheBasedir() + "/" + resolvedFileName);

			String destPath = FilenameUtils.getFullPath(IvyPatternHelper.substitute(destArtifactPattern, artifact));

			DependencyResolver dependencyResolver = Installer.this.ivySettings.getResolver("installed");

			try {
				ZipFile zipFile = new ZipFile(resolvedFile);
				Enumeration<? extends ZipEntry> entries = zipFile.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					File entryDestination = new File(destPath, entry.getName());
					entryDestination.getParentFile().mkdirs();
					InputStream in = zipFile.getInputStream(entry);
					OutputStream out = new FileOutputStream(entryDestination);
					IOUtils.copy(in, out);
					IOUtils.closeQuietly(in);
					IOUtils.closeQuietly(out);
				}
			} catch (ZipException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}
	}

}
