package gsn.gui;

import gsn.Main;
import gsn.beans.ContainerConfig;
import gsn.gui.forms.GSNConfiguratorPanel;
import gsn.utils.ValidityTools;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.MenuItem;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.FileNotFoundException;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import org.jibx.runtime.JiBXException;

import com.jgoodies.looks.HeaderStyle;
import com.jgoodies.looks.Options;
import com.jgoodies.looks.plastic.PlasticLookAndFeel;
import com.jgoodies.looks.plastic.PlasticXPLookAndFeel;
import com.jgoodies.looks.plastic.theme.DesertBluer;

public class GSNConfiguratorFrame extends JFrame {

	// The swing components for this Frame
	private Container contentPane;

	private GSNConfiguratorPanel configuratorPanel;

	private GSNStatusBar statusBar;

	private JMenuBar menuBar;

	private JMenu menuFile, menuEdit, menuOptions, menuHelp;

	private JMenuItem menuNewConfig, menuSave, menuQuit;

	private JMenuItem menuUndo, menuRedo;

	private JMenuItem menuHelpContents, menuAbout;


	// We load all icons here so that we can reuse them
	public static final Icon GSN_ICON = new ImageIcon("icons/gsn.png");

	// Menus titles (for future i18n/l10n)
	private static final String MENU_FILE = "File", MENU_EDIT = "Edit",
	MENU_OPTIONS = "Options", MENU_HELP = "Help";

	private static final String MENU_NEWCONFIG = "New Configuration",
	MENU_SAVE = "Save Configuration", MENU_QUIT = "Quit", MENU_UPDATE="Update";

	private static final String MENU_UNDO = "Undo Action",
	MENU_REDO = "Redo Action", MENU_HELPCONTENTS = "Help Contents...",
	MENU_ABOUT = "About";

	// about dialog text in html
//	private static final String HTML_CODE_ABOUT_DIALOG = "<h1>GSN Control Center</h1><br>"
//	+ "<p>This software has been brought to you by "
//	+ "<a href=\"http://www.xoben.com\">Xoben Technology</a> (http://www.xoben.com).<br>"
//	+ "The Global Sensor Networks (GSN) engine is free software (GPL license) ;<br> the project is hosted at http://globalsn.sourceforge.net .</p>"
//	+ "<br><br><p>&copy; 2006-2007.</p><br>";


	// menus accelerators / mnemonics
	private static final int ACCEL_FILE = KeyEvent.VK_F;

	private static final KeyStroke ACCEL_QUIT = KeyStroke.getKeyStroke(
			new Character('q'), java.awt.event.InputEvent.CTRL_MASK);

	private static final int ACCEL_EDIT = KeyEvent.VK_E;

	private static final int ACCEL_HELP = KeyEvent.VK_H;

	/*
	 * Initialize the frame window, close operations, menus, look&feel
	 */
	public GSNConfiguratorFrame(String containerConfigXML, String gsnLog4j,
			String dirLog4j) throws FileNotFoundException, JiBXException {
		super("GSN Middleware GUI");
      ContainerConfig bean = ContainerConfig
		.getConfigurationFromFile(containerConfigXML, gsnLog4j,
				dirLog4j);
		configuratorPanel = new GSNConfiguratorPanel(bean);
		initComponents();
		initEvents();
		setVisible(true);
	}

	private void initEvents() {
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		this.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				closeGSNConfigurator();
			}
		});
	}

	private void initComponents() {
		setSize(800, 600);
		contentPane = getContentPane();
		contentPane.setLayout(new BorderLayout());
		contentPane.add(configuratorPanel.getPanel());
		initMenuBar();
		setJMenuBar(menuBar);
		initStatusBar();
		contentPane.add(statusBar, BorderLayout.SOUTH);

	}

	/**
	 * 
	 */
	private void initStatusBar() {
		statusBar = new GSNStatusBar();
		configuratorPanel.registerInterestInStartStopState(statusBar);
	}

	private void initMenuBar() {
		menuBar = new JMenuBar();

		menuFile = new JMenu(MENU_FILE);
//		menuNewConfig = new JMenuItem(MENU_NEWCONFIG);

		menuQuit = new JMenuItem(MENU_QUIT);
		menuQuit.setAccelerator(ACCEL_QUIT);

		menuQuit.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				closeGSNConfigurator();
			}

		});

		menuFile.setMnemonic(ACCEL_FILE);
//		menuFile.add(menuNewConfig);
		menuFile.add(new JMenuItem(new UpdateAction()));
		menuFile.add(new JMenuItem(new AboutAction(getBackground())));
		menuFile.addSeparator();
		menuFile.add(menuQuit);
		menuBar.add(menuFile);
		menuBar.putClientProperty(Options.HEADER_STYLE_KEY, HeaderStyle.BOTH);

	}

	public static void main(String[] args)  {
		ValidityTools.checkAccessibilityOfFiles(Main.DEFAULT_GSN_CONF_FILE,
				Main.DEFAULT_GSN_LOG4J_PROPERTIES,
		"conf/log4j.directory.properties");
		ValidityTools
		.checkAccessibilityOfDirs(Main.DEFAULT_VIRTUAL_SENSOR_DIRECTORY);
		PlasticLookAndFeel laf = new PlasticXPLookAndFeel();
		PlasticLookAndFeel.setCurrentTheme(new DesertBluer());
		try {
			UIManager.setLookAndFeel(laf);
		} catch (UnsupportedLookAndFeelException e1) {
			e1.printStackTrace();
		}
		
		try {
		new GSNConfiguratorFrame(Main.DEFAULT_GSN_CONF_FILE,
				Main.DEFAULT_GSN_LOG4J_PROPERTIES,
		"conf/log4j.directory.properties");
		} catch(FileNotFoundException exception) {
			System.out.println("Configuration file could not be found ! Stopping now (Error message: " + exception.getMessage());
			exception.getStackTrace();
		} catch(JiBXException exception) {
			System.out.println("GSN Configurator encountered an error. Please report it to the gsn team at http://globalsn.sourceforge.net. Error message was: " + exception.getMessage());
			exception.getStackTrace();
			
		}
	}

	/*
	 * This is the central point of exit.
	 */
	private void closeGSNConfigurator() {
		JOptionPane confirmExitPane = new JOptionPane(
				"Are you sure you want to exit GSN Control Center ? GSN and GSN-DIR will be stopped.",
				JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION,
				GSN_ICON);
		JDialog confirmExitDialog = confirmExitPane.createDialog(null,
		"Please confirm exit request");
		confirmExitDialog.setVisible(true);
		Object selectedValue = confirmExitPane.getValue();
		if (selectedValue != null
				&& ((Integer) selectedValue).intValue() == JOptionPane.OK_OPTION) {
			System.exit(0);
		}
	}
}
class UpdateAction extends AbstractAction{
	public UpdateAction() {
		super("Update");
	}
	public void actionPerformed(ActionEvent action) {
		try {
			if (JOptionPane.showConfirmDialog(null, "Do you want to update the GSN ?","Updating",JOptionPane.YES_NO_OPTION) == JOptionPane.NO_OPTION)
				return;
			AntRunner.blockingAntTaskExecution(null, "update");
			JOptionPane.showMessageDialog(null, "Update done, please restart the GSN now.","Update status",JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}

class AboutAction extends AbstractAction {
	private static final String HTML_CODE_ABOUT_DIALOG = "<h1>GSN Control Center</h1><br>"
		+ "<p>This software has been developed by "
		+ "the GSN Development Team ( http://globalsn.sourceforge.net ).<br>";
	private Color bgColor;
	public AboutAction(Color bgColor) {
		super("About");
		this.bgColor = bgColor;
	}
	public void actionPerformed(ActionEvent e) {
		JTextPane aboutTextPane = new JTextPane();
		aboutTextPane.setContentType("text/html");
		aboutTextPane.setText(HTML_CODE_ABOUT_DIALOG);
		aboutTextPane.setBackground(bgColor);
		JOptionPane aboutPane = new JOptionPane(aboutTextPane,
				JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION,
				GSNConfiguratorFrame.GSN_ICON);
		JDialog aboutDialog = aboutPane.createDialog(null,
		"About GSN Configurator");
		aboutDialog.setVisible(true);
	}	
}
