package gsn;

import gsn.beans.ContainerConfig;
import gsn.beans.StorageConfig;
import gsn.beans.VSensorConfig;
import gsn.http.rest.LocalDeliveryWrapper;
import gsn.http.rest.PushDelivery;
import gsn.http.rest.RestDelivery;
import gsn.storage.SQLValidator;
import gsn.storage.StorageManager;
import gsn.storage.StorageManagerFactory;
import gsn.utils.ValidityTools;
import gsn.vsensor.SQLValidatorIntegration;
import gsn.wrappers.WrappersUtil;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.SplashScreen;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;
import java.util.Random;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.session.HashSessionIdManager;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.jibx.runtime.BindingDirectory;
import org.jibx.runtime.IBindingFactory;
import org.jibx.runtime.IUnmarshallingContext;
import org.jibx.runtime.JiBXException;


/**
 * Web Service URL : http://localhost:22001/services/Service?wsdl
 *
 */
public final class Main {

	private static Main singleton ;

	private static int gsnControllerPort;

    private Main() throws Exception {

		ValidityTools.checkAccessibilityOfFiles ( DEFAULT_GSN_LOG4J_PROPERTIES , WrappersUtil.DEFAULT_WRAPPER_PROPERTIES_FILE , DEFAULT_GSN_CONF_FILE );
		ValidityTools.checkAccessibilityOfDirs ( DEFAULT_VIRTUAL_SENSOR_DIRECTORY );
		PropertyConfigurator.configure ( Main.DEFAULT_GSN_LOG4J_PROPERTIES );
		//  initializeConfiguration();
		try {
			controlSocket = new GSNController(null, gsnControllerPort);
			containerConfig = loadContainerConfiguration();
			updateSplashIfNeeded(new String[] {"GSN is starting at port:"+containerConfig.getContainerPort(),"All GSN logs are available at: logs/gsn.log"});
			System.out.println("Global Sensor Networks (GSN) is Starting on port "+containerConfig.getContainerPort()+"...");
			System.out.println("The logs of GSN server are available in logs/gsn.log file.");
			System.out.println("To Stop GSN execute the gsn-stop script.");
		} catch ( FileNotFoundException e ) {
			logger.error ( new StringBuilder ( ).append ( "The the configuration file : conf/gsn.xml").append ( " doesn't exist." ).toString ( ) );
			logger.error ( e.getMessage ( ) );
			logger.error ( "Check the path of the configuration file and try again." );
			if ( logger.isDebugEnabled ( ) ) logger.debug ( e.getMessage ( ) , e );
			throw new Exception(e);
		}
        int maxDBConnections = System.getProperty("maxDBConnections") == null ? DEFAULT_MAX_DB_CONNECTIONS : Integer.parseInt(System.getProperty("maxDBConnections"));
        int maxServlets = System.getProperty("maxServlets") == null ? DEFAULT_JETTY_SERVLETS : Integer.parseInt(System.getProperty("maxServlets"));

        mainStorage = StorageManagerFactory.getInstance(containerConfig.getStorage().getJdbcDriver ( ) , containerConfig.getStorage().getJdbcUsername ( ) , containerConfig.getStorage().getJdbcPassword ( ) , containerConfig.getStorage().getJdbcURL ( ) , maxDBConnections);
        //
        StorageConfig sc = containerConfig.getSliding() != null ? containerConfig.getSliding().getStorage() : containerConfig.getStorage() ;
        windowStorage = StorageManagerFactory.getInstance(sc.getJdbcDriver ( ) , sc.getJdbcUsername ( ) , sc.getJdbcPassword ( ) , sc.getJdbcURL ( ), Main.DEFAULT_MAX_DB_CONNECTIONS);
        
        if ( logger.isInfoEnabled ( ) ) logger.info ( "The Container Configuration file loaded successfully." );

		try {
			logger.debug("Starting the http-server @ port: "+containerConfig.getContainerPort()+" (maxDBConnections: "+maxDBConnections+", maxServlets:"+maxServlets+")"+" ...");
            Server jettyServer = getJettyServer(Main.getContainerConfig().getContainerPort(), maxServlets);
			jettyServer.start ( );
			logger.debug("http-server running @ port: "+containerConfig.getContainerPort());
		} catch ( Exception e ) {
			throw new Exception("Start of the HTTP server failed. The HTTP protocol is used in most of the communications: "+ e.getMessage(),e);
		}
		VSensorLoader vsloader = VSensorLoader.getInstance ( DEFAULT_VIRTUAL_SENSOR_DIRECTORY );
		controlSocket.setLoader(vsloader);

		String msrIntegration = "gsn.msr.sensormap.SensorMapIntegration";
		try {
			vsloader.addVSensorStateChangeListener((VSensorStateChangeListener) Class.forName(msrIntegration).newInstance());	
		}catch (Exception e) {
			logger.warn("MSR Sensor Map integration is disabled.");
		}

		vsloader.addVSensorStateChangeListener(new SQLValidatorIntegration(SQLValidator.getInstance()));
		vsloader.addVSensorStateChangeListener(DataDistributer.getInstance(LocalDeliveryWrapper.class));
		vsloader.addVSensorStateChangeListener(DataDistributer.getInstance(PushDelivery.class));
		vsloader.addVSensorStateChangeListener(DataDistributer.getInstance(RestDelivery.class));

		ContainerImpl.getInstance().addVSensorDataListener(DataDistributer.getInstance(LocalDeliveryWrapper.class));
		ContainerImpl.getInstance().addVSensorDataListener(DataDistributer.getInstance(PushDelivery.class));
		ContainerImpl.getInstance().addVSensorDataListener(DataDistributer.getInstance(RestDelivery.class));
		vsloader.startLoading();


	}

	private static void closeSplashIfneeded() {
		if (isHeadless())
			return;
		SplashScreen splash = SplashScreen.getSplashScreen();
		//Check if we have specified any splash screen
		if (splash == null) {
			return;
		}
		if (splash.isVisible())
			splash.close();
	}
	
	

	private static void updateSplashIfNeeded(String message[]) {
		boolean headless_check = isHeadless();
		for (int i=0;i<message.length;i++)
			System.out.println(message[i]);

		if (!headless_check) {
			SplashScreen splash = SplashScreen.getSplashScreen();
			if (splash == null)
				return;
			if (splash.isVisible()) {
				//Get a graphics overlay for the splash screen
				Graphics2D g = splash.createGraphics();
				//Do some drawing on the graphics object
				//Now update to the splash screen

				g.setComposite(AlphaComposite.Clear);
				g.fillRect(0,0,400,70);
				g.setPaintMode();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(Color.BLACK);
				g.setFont(new Font("Arial",Font.BOLD,11));
				for (int i=0;i<message.length;i++)
					g.drawString(message[i], 13, 16*i+10);
				splash.update();
			}
		}
	}

	private static boolean isHeadless() {
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment(); 
		boolean headless_check = ge.isHeadless();
		return headless_check;
	}

	public synchronized static Main getInstance() {
		if (singleton==null)
			try {
				singleton=new Main();
			} catch (Exception e) {
				logger.error(e.getMessage(),e);
				throw new RuntimeException(e);
			}
			return singleton;
	}

    private static StorageManager mainStorage = null;

    private static StorageManager windowStorage = null;

	private GSNController controlSocket;

    private static final int DEFAULT_JETTY_SERVLETS = 100;

    public static final int DEFAULT_MAX_DB_CONNECTIONS = 8;

	public static final String     DEFAULT_GSN_LOG4J_PROPERTIES     = "conf/log4j.properties";

	public static transient Logger logger= Logger.getLogger ( Main.class );

	public static final String     DEFAULT_GSN_CONF_FILE            = "conf/gsn.xml";

	public static String     DEFAULT_VIRTUAL_SENSOR_DIRECTORY = "virtual-sensors";

	public static final String     DEFAULT_WEB_APP_PATH             = "webapp";

	public static void main ( String [ ]  args)  {
		Main.gsnControllerPort = Integer.parseInt(args[0]) ;
		updateSplashIfNeeded(new String[] {"GSN is trying to start.","All GSN logs are available at: logs/gsn.log"});
		try {
			Main.getInstance();
		}catch (Exception e) {
			updateSplashIfNeeded(new String[] {"Starting GSN failed! Look at logs/gsn.log for more information."});
			try {
				Thread.sleep(4000);
			} catch (InterruptedException e1) {
				e1.printStackTrace();
			}
		}
		closeSplashIfneeded();
	}

	/**
	 * Mapping between the wrapper name (used in addressing of stream source)
	 * into the class implementing DataSource.
	 */
	private static  Properties wrappers ;

	private  ContainerConfig                       containerConfig;

	private  HashMap < String , VSensorConfig >    virtualSensors;

	public static ContainerConfig loadContainerConfiguration() throws InvalidKeyException, NoSuchAlgorithmException, NoSuchProviderException, KeyStoreException, CertificateException, SecurityException, SignatureException, IOException{
		ValidityTools.checkAccessibilityOfFiles ( Main.DEFAULT_GSN_LOG4J_PROPERTIES , WrappersUtil.DEFAULT_WRAPPER_PROPERTIES_FILE , Main.DEFAULT_GSN_CONF_FILE );
		ValidityTools.checkAccessibilityOfDirs ( Main.DEFAULT_VIRTUAL_SENSOR_DIRECTORY );
		PropertyConfigurator.configure ( Main.DEFAULT_GSN_LOG4J_PROPERTIES );
		ContainerConfig toReturn = null;
		try {
			toReturn = loadContainerConfig (DEFAULT_GSN_CONF_FILE );
			wrappers = WrappersUtil.loadWrappers(new HashMap<String, Class<?>>());
			if ( logger.isInfoEnabled ( ) ) logger.info ( new StringBuilder ( ).append ( "Loading wrappers.properties at : " ).append ( WrappersUtil.DEFAULT_WRAPPER_PROPERTIES_FILE ).toString ( ) );
			if ( logger.isInfoEnabled ( ) ) logger.info ( "Wrappers initialization ..." );
		} catch ( JiBXException e ) {
			logger.error ( e.getMessage ( ) );
			logger.error ( new StringBuilder ( ).append ( "Can't parse the GSN configuration file : conf/gsn.xml" ).toString ( ) );
			logger.error ( "Please check the syntax of the file to be sure it is compatible with the requirements." );
			logger.error ( "You can find a sample configuration file from the GSN release." );
			if ( logger.isDebugEnabled ( ) ) logger.debug ( e.getMessage ( ) , e );
			System.exit ( 1 );
		} catch ( FileNotFoundException e ) {
			logger.error ( new StringBuilder ( ).append ( "The the configuration file : conf/gsn.xml").append ( " doesn't exist." ).toString ( ) );
			logger.error ( e.getMessage ( ) );
			logger.error ( "Check the path of the configuration file and try again." );
			if ( logger.isDebugEnabled ( ) ) logger.debug ( e.getMessage ( ) , e );
			System.exit ( 1 );
		} catch ( ClassNotFoundException e ) {
			logger.error ( "The file wrapper.properties refers to one or more classes which don't exist in the classpath");
			logger.error ( e.getMessage ( ),e );
			System.exit ( 1 );
		}finally {
			return toReturn;
		}
	}

	/**
	 * This method is called by Rails's Application.rb file.
	 */
	public static ContainerConfig loadContainerConfig (String gsnXMLpath) throws JiBXException, FileNotFoundException, NoSuchAlgorithmException, NoSuchProviderException, IOException, KeyStoreException, CertificateException, SecurityException, SignatureException, InvalidKeyException, ClassNotFoundException {
		if (!new File(gsnXMLpath).isFile()) {
			logger.fatal("Couldn't find the gsn.xml file @: "+(new File(gsnXMLpath).getAbsolutePath()));
			System.exit(1);
		}
		IBindingFactory bfact = BindingDirectory.getFactory ( ContainerConfig.class );
		IUnmarshallingContext uctx = bfact.createUnmarshallingContext ( );
		ContainerConfig conf = ( ContainerConfig ) uctx.unmarshalDocument ( new FileInputStream ( new File ( gsnXMLpath ) ) , null );
		Class.forName(conf.getStorage().getJdbcDriver());
		conf.setContainerConfigurationFileName (  gsnXMLpath );
		return conf;
	}

	//FIXME: COPIED_FOR_SAFE_STOAGE
	public static Properties getWrappers()  {
		if (singleton==null )
			return WrappersUtil.loadWrappers(new HashMap<String, Class<?>>());
		return singleton.wrappers;
	}

	private static final String PUBLIC_KEY_FILE=".public_key";

	private static final String PRIVATE_KEY_FILE=".private_key";

	public static void initPKI ( String publicKeyFile,String privateKeyFile ) throws NoSuchAlgorithmException , NoSuchProviderException , FileNotFoundException , IOException, KeyStoreException, CertificateException, SecurityException, SignatureException, InvalidKeyException {
		// TODO  : Use the pri/pub keys if they exist. (needs verification first).
		KeyPairGenerator keyGen = KeyPairGenerator.getInstance ( "DSA" , "SUN" );
		SecureRandom random = SecureRandom.getInstance ( "SHA1PRNG" , "SUN" );
		keyGen.initialize ( 512 , random );
		KeyPair pair = keyGen.generateKeyPair ( );
		PrivateKey priv = pair.getPrivate ( );
		PublicKey pub = pair.getPublic ( );
		CertificateFactory certificateFactory =  CertificateFactory.getInstance ("X.509");
		File privateF = new File (privateKeyFile);
		File publicF = new File (publicKeyFile);
		publicF.createNewFile ();
		privateF.createNewFile ();
		OutputStream output = new FileOutputStream (privateF );
		output.write ( priv.getEncoded ( ) );
		output.close ( );
		output = new FileOutputStream ( publicF );
		output.write ( pub.getEncoded ( ) );
		output.close ( );
		KeyStore ksca = KeyStore.getInstance ("JKS","SUN");
		ksca.load (null,null);
		logger.warn ("Public and Private keys are generated successfully.");
	}

	private static PrivateKey readPrivateKey () throws FileNotFoundException, IOException, NoSuchAlgorithmException, InvalidKeySpecException{
		FileInputStream keyfis = new FileInputStream (PRIVATE_KEY_FILE);
		byte[] encKey = new byte[keyfis.available ()];
		keyfis.read (encKey);
		keyfis.close ();
		PKCS8EncodedKeySpec privKeySpec = new PKCS8EncodedKeySpec (encKey);
		KeyFactory keyFactory = KeyFactory.getInstance ("DSA");
		return keyFactory.generatePrivate (privKeySpec);
	}

	private static PublicKey readPublicKey () throws IOException, NoSuchAlgorithmException, InvalidKeySpecException{
		FileInputStream keyfis = new FileInputStream (PUBLIC_KEY_FILE);
		byte[] encKey = new byte[keyfis.available ()];
		keyfis.read (encKey);
		keyfis.close ();
		PKCS8EncodedKeySpec pubKeySpec = new PKCS8EncodedKeySpec (encKey);
		KeyFactory keyFactory = KeyFactory.getInstance ("DSA");
		return keyFactory.generatePublic (pubKeySpec);
	}
	//FIXME: COPIED_FOR_SAFE_STOAGE
	public  static Class < ? > getWrapperClass ( String id ) {
		try {
			String className =  getWrappers().getProperty(id);
			if (className ==null) { 
				logger.error("The requested wrapper: "+id+" doesn't exist in the wrappers.properties file.");
				return null;
			}

			return Class.forName(className);  
		} catch (ClassNotFoundException e) {
			logger.error(e.getMessage(),e);
		}
		return null;
	}

	public final HashMap < String , VSensorConfig > getVirtualSensors ( ) {
		return virtualSensors;
	}

	public  boolean justConsumes ( ) {
		Iterator < VSensorConfig > vsconfigs = virtualSensors.values ( ).iterator ( );
		while ( vsconfigs.hasNext ( ) )
			if ( !vsconfigs.next ( ).needsStorage ( ) ) return false;
		return true;
	}

	/**
	 * Get's the GSN configuration without starting GSN.
	 * @return
	 * @throws Exception
	 */
	public static ContainerConfig getContainerConfig() {
		if (singleton == null)
			try {
				return loadContainerConfig(DEFAULT_GSN_CONF_FILE);
			} catch (Exception e) {
				return null;
			}
			else
				return singleton.containerConfig;
	}

    /**
     * Mote to storage Manager
     * @param length
     * @return
     */
	public static String randomTableNameGenerator ( int length ) {
		byte oneCharacter;
		StringBuffer result = new StringBuffer ( length );
		for ( int i = 0 ; i < length ; i++ ) {
			oneCharacter = ( byte ) ( ( Math.random ( ) * ( 'z' - 'a' + 1 ) ) + 'a' );
			result.append ( ( char ) oneCharacter );
		}
		return result.toString ( );
	}

    /**
     * TODO Move to storage ,manager
     * @return
     */
	public static int tableNameGenerator ( ) {
		return randomTableNameGenerator ( 15 ).hashCode ( );
	}
	/**
     * TODO Move to Storage Manager
	 * This method is used ONLY for ORACLE DB.
	 * ADDS the postfix at the end of the tableName. If the table name ends with " then
	 * updates it properly.   
	 * @param table_name
	 * @return
	 */
	public static String tableNamePostFixAppender(CharSequence table_name,String postFix) {
		String tableName = table_name.toString();
		if (tableName.endsWith("\""))
			return (tableName.substring(0, tableName.length()-2))+postFix+"\"";
		else
			return tableName+postFix;
	}

    /**
     * TODO Move to storage manager
     * @param tableName
     * @return
     */
	public static StringBuilder tableNameGeneratorInString (CharSequence tableName) {
		if (tableName.charAt(0)=='_' && getMainStorage().isOracle())
			return new StringBuilder( "\"").append(tableName).append("\"");
		return new StringBuilder(tableName);
	}

    /**
     * TODO Move to storage manager
     * @param code
     * @return
     */
	public static StringBuilder tableNameGeneratorInString (int code) {
		StringBuilder sb = new StringBuilder ("_");
		if (code<0)
			sb.append ( "_" );
		sb.append ( Math.abs (code) );
		return tableNameGeneratorInString(sb);
	}

	public Server getJettyServer(int port, int maxThreads) throws IOException {
		Server server = new Server();
		Connector connector=new SelectChannelConnector();//new SocketConnector ();//using basic connector for windows bug; Fast option=>SelectChannelConnector
		HandlerCollection handlers = new HandlerCollection();
		ContextHandlerCollection contexts = new ContextHandlerCollection();
		server.setThreadPool(new QueuedThreadPool(maxThreads));
		connector.setPort ( port );

		SslSocketConnector sslSocketConnector = null;
		if (getContainerConfig().getSSLPort()>10){
			sslSocketConnector = new SslSocketConnector();
			sslSocketConnector.setKeystore("conf/gsn.jks");
			sslSocketConnector.setKeyPassword(getContainerConfig().getSSLKeyPassword());
			sslSocketConnector.setPassword(getContainerConfig().getSSLKeyStorePassword());
			sslSocketConnector.setPort(getContainerConfig().getSSLPort());
		}

		if (sslSocketConnector==null)
			server.setConnectors ( new Connector [ ] { connector } );
		else
			server.setConnectors ( new Connector [ ] { connector,sslSocketConnector } );

		WebAppContext webAppContext = new WebAppContext(contexts, DEFAULT_WEB_APP_PATH ,"/");

		handlers.setHandlers(new Handler[]{contexts,new DefaultHandler()});
		server.setHandler(handlers);

		Properties usernames = new Properties();
		usernames.load(new FileReader("conf/realm.properties"));
		if (!usernames.isEmpty()){
			HashLoginService loginService = new HashLoginService();
			loginService.setName("GSNRealm");
			loginService.setConfig("conf/realm.properties");
			loginService.setRefreshInterval(10000); //re-reads the file every 10 seconds.

			Constraint constraint = new Constraint();
			constraint.setName("GSN User");
			constraint.setRoles(new String[]{"gsnuser"});
			constraint.setAuthenticate(true);

			ConstraintMapping cm = new ConstraintMapping();
			cm.setConstraint(constraint);
			cm.setPathSpec("/*");
			cm.setMethod("GET");

			ConstraintMapping cm2 = new ConstraintMapping();
			cm2.setConstraint(constraint);
			cm2.setPathSpec("/*");
			cm2.setMethod("POST");

			ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
			securityHandler.setLoginService(loginService);
			securityHandler.setConstraintMappings(new ConstraintMapping[]{cm, cm2});
			securityHandler.setAuthenticator(new BasicAuthenticator());
			webAppContext.setSecurityHandler(securityHandler);
		}

		server.setSendServerVersion(true);
		server.setStopAtShutdown ( true );
		server.setSendServerVersion ( false );
		server.setSessionIdManager(new HashSessionIdManager(new Random()));

		return server;
	}

    public static StorageManager getMainStorage() {
        return mainStorage;    
    }

    public static StorageManager getWindowStorage() {
        return windowStorage;
    }
}