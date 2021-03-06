package org.osehra.eclipse.atfrecorder;

import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Panel;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.ISourceProviderListener;
import org.eclipse.ui.services.ISourceProviderService;
import org.osehra.eclipse.atfrecorder.actions.RecordingIconAction;
import org.osehra.eclipse.atfrecorder.internal.ScreenStateSourceProvider;

import com.jcraft.jcterm.Connection;
import com.jcraft.jcterm.Emulator;
import com.jcraft.jcterm.EmulatorVT100;
import com.jcraft.jcterm.Splash;
import com.jcraft.jcterm.Term;

public class ATFRecorderAWT extends Panel implements KeyListener, Term,
		ISourceProviderListener {

	private static final long serialVersionUID = 8029208716727234045L;

	static String COPYRIGHT = "JCTerm 0.0.11\nCopyright (C) 2002,2007 ymnk<ymnk@jcraft.com>, JCraft,Inc.\n"
			+ "Official Homepage: http://www.jcraft.com/jcterm/\n"
			+ "This software is licensed under GNU LGPL.";

	private OutputStream out;
	private InputStream in;
	Emulator emulator = null;

	Connection connection = null;

	private BufferedImage img;
	private Graphics2D cursor_graphics;
	private Graphics2D graphics;
	private java.awt.Color defaultbground = Color.black;
	private java.awt.Color defaultfground = Color.white;
	private java.awt.Color bground = Color.black;
	private java.awt.Color fground = Color.white;
	private java.awt.Component term_area = null;
	private java.awt.Font font;

	private boolean bold = false;
	private boolean underline = false;
	private boolean reverse = false;

	private int term_width = 80;
	private int term_height = 24;

	private int x = 0;
	private int y = 0;
	private int descent = 0;

	private int char_width;
	private int char_height;

	private boolean antialiasing = true;
	// private int line_space=0;
	private int line_space = -2;
	private int compression = 0;

	private Splash splash = null;

	// Added for Recording
	private RecordingIconAction recordingIcon;
	
	private boolean recordingEnabled; //turns recording of terminal session on or off. set by user via stop button
	private String currentScreen = "";
	
	private AVCodeStateEnum avCodeState = AVCodeStateEnum.NO_PROMPT;
	private String capturedAccessCode;
	private String capturedVerifyCode;
	
	private boolean disableScreenRecording = false; // set to true to disable
													// echoing commands into
													// screen
	private String currentCommand = "";
	private List<String> currentSelectedExpect = new ArrayList<String>();
	private TestRecording testRecording = new TestRecording();
	private ScreenStateSourceProvider screenStateService;
	private ScreenStateSourceProvider selectedTextService;
	
	//a lot of this can be re-designed/refactored
//	private RecordingIconAction recordingIcon;
//	private StopIconAction stopIcon;

	public TestRecording getCurrentRecording() {
		return testRecording;
	}

	public void resetRecorder() {
		testRecording.setAccessCode(null);
		testRecording.setVerifyCode(null);
		if (testRecording.getEvents() != null)
			testRecording.getEvents().clear();
		avCodeState = AVCodeStateEnum.NO_PROMPT;
		recordingIcon.toggleOn();
	}

	private final Object[] colors = { Color.black, Color.red, Color.green,
			Color.yellow, Color.blue, Color.magenta, Color.cyan, Color.white };

	public ATFRecorderAWT(ISourceProviderService sourceProviderService) {
		enableEvents(AWTEvent.KEY_EVENT_MASK);

		setFocusable(true);
		setFocusTraversalKeysEnabled(false);
		screenStateService = (ScreenStateSourceProvider) sourceProviderService
				.getSourceProvider(ScreenStateSourceProvider.NAME_SCREEN);
		selectedTextService = (ScreenStateSourceProvider) sourceProviderService
				.getSourceProvider(ScreenStateSourceProvider.NAME_SELECTED); //why is it looked up like this?? and does it even matter what string is passed in?

		// register our Listener View (so it can get updates to the current screen)
		selectedTextService.addSourceProviderListener(this);
	}

	private void setFont(String fname) {
		font = java.awt.Font.decode(fname);
		Image img = createImage(1, 1);
		Graphics graphics = img.getGraphics();
		graphics.setFont(font);
		{
			FontMetrics fo = graphics.getFontMetrics();
			descent = fo.getDescent();
			// System.out.println(fo.getDescent());
			// System.out.println(fo.getAscent());
			// System.out.println(fo.getLeading());
			// System.out.println(fo.getHeight());
			// System.out.println(fo.getMaxAscent());
			// System.out.println(fo.getMaxDescent());
			// System.out.println(fo.getMaxDecent());
			// System.out.println(fo.getMaxAdvance());
			char_width = (int) (fo.charWidth((char) '@'));
			char_height = (int) (fo.getHeight()) + (line_space * 2);
			// descent+=line_space;
		}
		img.flush();
		graphics.dispose();
	}

	void initGraphics() {
		setFont("Monospaced-14");

		img = new BufferedImage(getTermWidth(), getTermHeight(),
				BufferedImage.TYPE_INT_RGB);
		graphics = (Graphics2D) (img.getGraphics());
		graphics.setFont(font);
		if (splash != null)
			splash.draw(img, getTermWidth(), getTermHeight());
		else
			clear();
		cursor_graphics = (Graphics2D) (img.getGraphics());
		cursor_graphics.setColor(getForeGround());
		cursor_graphics.setXORMode(getBackGround());

		term_area = this;

		Panel panel = this;
		panel.setSize(getTermWidth(), getTermHeight());
		panel.setFocusable(true);
	}

	public void setSize(int w, int h) {

		super.setSize(w, h);

		if (img == null) {
			initGraphics();
		}

		Image imgOrg = img;
		if (graphics != null)
			graphics.dispose();

		int column = w / getCharWidth();
		int row = h / getCharHeight();

		term_width = column;
		term_height = row;

		if (emulator != null)
			emulator.reset();

		img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		graphics = (Graphics2D) (img.getGraphics());
		graphics.setFont(font);

		clear_area(0, 0, w, h);

		if (imgOrg != null) {
			Shape clip = graphics.getClip();
			graphics.setClip(0, 0, getTermWidth(), getTermHeight());
			graphics.drawImage(imgOrg, 0, 0, term_area);
			graphics.setClip(clip);
		}

		if (cursor_graphics != null)
			cursor_graphics.dispose();

		cursor_graphics = (Graphics2D) (img.getGraphics());
		cursor_graphics.setColor(getForeGround());
		cursor_graphics.setXORMode(getBackGround());

		setAntiAliasing(antialiasing);

		if (connection != null) {
			connection.requestResize(this);
		}

		if (imgOrg != null) {
			imgOrg.flush();
			imgOrg = null;
		}

	}

	public void setFrame(java.awt.Component term_area) {
		this.term_area = term_area;
	}

	public void start(Connection connection) {
		this.connection = connection;
		in = connection.getInputStream();
		out = connection.getOutputStream();
		emulator = new EmulatorVT100(this, in);
		emulator.reset();
		emulator.start();

		if (splash != null)
			splash.draw(img, getTermWidth(), getTermHeight());
		else
			clear();
		redraw(0, 0, getTermWidth(), getTermHeight());

		requestFocus();
	}

	public void update(Graphics g) {
		if (img == null) {
			initGraphics();
		}
		g.drawImage(img, 0, 0, term_area);
	}

	public void paint(Graphics g) {
		if (img == null) {
			initGraphics();
		}
		g.drawImage(img, 0, 0, term_area);
	}

	public void processKeyEvent(KeyEvent e) {
		System.out.println(e);
		int id = e.getID();
		if (id == KeyEvent.KEY_PRESSED) {
			//keyPressed(e);
		} else if (id == KeyEvent.KEY_RELEASED) {
			//because ctrl + [some character] will never register as a KEY_TYPED event
			if (e.getModifiers() == KeyEvent.CTRL_MASK /* || e.getModifiers() == KeyEvent.SHIFT_DOWN_MASK*/)
				keyTyped(e);
			//keyReleased(e);
		} else if (id == KeyEvent.KEY_TYPED) {
			keyTyped(e);
		}
		e.consume(); // ??
	}

	byte[] obuffer = new byte[3];

	public boolean keyTypedCode(int keycode) {
		byte[] code = null;

		switch (keycode) {
		case KeyEvent.VK_CONTROL:
		case KeyEvent.VK_SHIFT:
		case KeyEvent.VK_ALT:
		case KeyEvent.VK_CAPS_LOCK:
			return true;
		case KeyEvent.VK_ENTER:
			code = emulator.getCodeENTER();
			break;
		case KeyEvent.VK_UP:
			code = emulator.getCodeUP();
			break;
		case KeyEvent.VK_DOWN:
			code = emulator.getCodeDOWN();
			break;
		case KeyEvent.VK_RIGHT:
			code = emulator.getCodeRIGHT();
			break;
		case KeyEvent.VK_LEFT:
			code = emulator.getCodeLEFT();
			break;
		case KeyEvent.VK_F1:
			code = emulator.getCodeF1();
			break;
		case KeyEvent.VK_F2:
			code = emulator.getCodeF2();
			break;
		case KeyEvent.VK_F3:
			code = emulator.getCodeF3();
			break;
		case KeyEvent.VK_F4:
			code = emulator.getCodeF4();
			break;
		case KeyEvent.VK_F5:
			code = emulator.getCodeF5();
			break;
		case KeyEvent.VK_F6:
			code = emulator.getCodeF6();
			break;
		case KeyEvent.VK_F7:
			code = emulator.getCodeF7();
			break;
		case KeyEvent.VK_F8:
			code = emulator.getCodeF8();
			break;
		case KeyEvent.VK_F9:
			code = emulator.getCodeF9();
			break;
		case KeyEvent.VK_F10:
			code = emulator.getCodeF10();
			break;
		case KeyEvent.VK_TAB:
			code = emulator.getCodeTAB();
			break;
		}
		if (code != null) {
			try {
				out.write(code, 0, code.length);
				out.flush();
			} catch (Exception ee) {
			}
			return true;
		}
		return false;
	}

	public void keyTyped(KeyEvent e) {
		int keycode = e.getKeyCode();
		char keychar = e.getKeyChar();
		//System.out.println(e);

		disableScreenRecording = true; // TODO: this isn't air-tight, user could
										// enter any key while we are still
										// waiting for the current screen to
										// come back

			
		if (keychar == '\n') {
			keycode = KeyEvent.VK_ENTER; // not sure why this bug exists from
			// JCTerm, but if the enter key is
			// pressed it is seen as a character
			// not a key code
			
			if (recordingEnabled) {
				try {
					recordCurrentScreen();
				} catch (RecordingException re) {
					return; //do not continue processing the enter key from the user/do not send enter to server
				}
			}
		}

		if (keychar == '\b' || keychar == 0x7F) { // backspace or delete pressed
			keychar = 0x7F;
			e.setKeyChar((char) 0x7F); // map backspace to delete key

			
			if (recordingEnabled && currentCommand.length() != 0) {
				// System.out.println("removing char: "
				// +currentCommand.substring(currentCommand.length() - 1));
				currentCommand = currentCommand.substring(0,
						currentCommand.length() - 1);
				// System.out.println(currentCommand);
			}

		}

		if (keyTypedCode(keycode))
			return;

		if (keychar != 0x7F) { // don't add the delete char to our command, but
								// do add it to the terminal out stream
			// System.out.println("adding keychar: " +keychar);
			if (recordingEnabled)
				currentCommand += keychar;
		}
		
//		if (ctrlPressed)
//			try {
//				out.write('^');
//			} catch (IOException e1) {
//			}
	
//		if (e.getModifiers() == KeyEvent.CTRL_MASK)
//			try {
//				System.out.println("Sending ^");
//			out.write('^');
//			e.setKeyChar(KeyEvent.getKeyText(e.getKeyCode()).toLowerCase().toCharArray()[0]); //reset the keyChar to its character value
//		} catch (IOException e1) {
//		}

		if ((keychar & 0xff00) == 0) {
			obuffer[0] = (byte) (e.getKeyChar());
			// System.out.println(Integer.toHexString(obuffer[0]));
			try {
				out.write(obuffer, 0, 1);
				out.flush();
			} catch (Exception ee) {
			}
		}

		// char keychar=e.getKeyChar();
		if ((keychar & 0xff00) != 0) {
			char[] foo = new char[1];
			foo[0] = keychar;
			try {
				byte[] goo = new String(foo).getBytes("EUC-JP");
				out.write(goo, 0, goo.length);
				out.flush();
			} catch (Exception eee) {
			}
		}
	}

	private void recordCurrentScreen() throws RecordingException {
		if (currentSelectedExpect != null && !currentSelectedExpect.isEmpty()) {
			
			if (currentScreen.trim().endsWith("ACCESS CODE:")) {
				capturedAccessCode = currentCommand;
				avCodeState = AVCodeStateEnum.FOUND_ACCESS_PROMPT;
			} else if (avCodeState == AVCodeStateEnum.FOUND_ACCESS_PROMPT && currentScreen.trim().equals("VERIFY CODE:")) {
				capturedVerifyCode = currentCommand;

				if (avCodeState == AVCodeStateEnum.PROMPT_PASSED) {
					Display.getDefault().asyncExec(new Runnable() {
						
						@Override
						public void run() {
							MessageDialog.openWarning(Display.getDefault().getActiveShell(), 
									"A/V code detected", 
									"While already logged into VistA, an ACCESS/VERIFY code prompt has been detected. Please save the current recording before continuing to a new login.");
							
							//could prompt user to save current test before starting a new login instead of instructing them to do so?
						}
					});
					
					throw new RecordingException(); //FYI: this prevents them from moving onto the next screen since the enter key will not be sent to the server
				}
				
				avCodeState = AVCodeStateEnum.FOUND_COMPLETE_PROMPT;
			} else if (avCodeState ==  AVCodeStateEnum.FOUND_ACCESS_PROMPT) {
				//TODO: need to fetch avCodePrevious state instead of assuming it was previously PROMPT_PASSED
				avCodeState = AVCodeStateEnum.PROMPT_PASSED; //reset back to PROMPT_PASSED, false positive on ACCESS CODE: match
			} else if (avCodeState ==  AVCodeStateEnum.FOUND_COMPLETE_PROMPT) {
				avCodeState = AVCodeStateEnum.PROMPT_PASSED;
				testRecording.setAccessCode(capturedAccessCode);
				testRecording.setVerifyCode(capturedVerifyCode);
			}
			
			if (recordingEnabled && avCodeState == AVCodeStateEnum.NO_PROMPT || avCodeState == AVCodeStateEnum.PROMPT_PASSED) {
				testRecording.getEvents().add(new RecordedExpectEvent(new ArrayList<String>(currentSelectedExpect)));
				List<String> commands = new ArrayList<String>();
				commands.add(currentCommand);
				testRecording.getEvents().add(new RecordedSendEvent(commands));
			}

			currentScreen = ""; // reset current screen buffer
			disableScreenRecording = false;
			currentCommand = "";
		} else {
			Display.getDefault().asyncExec(new Runnable() {
				
				@Override
				public void run() {
					MessageDialog.openWarning(Display.getDefault().getActiveShell(), 
							"No Input Detected", 
							"No input from the screen detected. Please ensure that the Expected Value View is displayed and that a value is selected.");
				}
			});
			throw new RecordingException(); //FYI: this prevents them from moving onto the next screen since the enter key will not be sent to the server
		}
	}

	public int getTermWidth() {
		return char_width * term_width;
	}

	public int getTermHeight() {
		return char_height * term_height;
	}

	public int getCharWidth() {
		return char_width;
	}

	public int getCharHeight() {
		return char_height;
	}

	public int getColumnCount() {
		return term_width;
	}

	public int getRowCount() {
		return term_height;
	}

	public void clear() {
		graphics.setColor(getBackGround());
		graphics.fillRect(0, 0, char_width * term_width, char_height
				* term_height);
		graphics.setColor(getForeGround());
	}

	public void setCursor(int x, int y) {
		this.x = x;
		this.y = y;
	}

	public void draw_cursor() {
		cursor_graphics.fillRect(x, y - char_height, char_width, char_height);
		repaint(x, y - char_height, char_width, char_height);
	}

	public void redraw(int x, int y, int width, int height) {
		repaint(x, y, width, height);
	}

	public void clear_area(int x1, int y1, int x2, int y2) {
		graphics.setColor(getBackGround());
		graphics.fillRect(x1, y1, x2 - x1, y2 - y1);
		graphics.setColor(getForeGround());
	}

	public void scroll_area(int x, int y, int w, int h, int dx, int dy) {
		graphics.copyArea(x, y, w, h, dx, dy);
	}

	public void drawBytes(byte[] buf, int s, int len, int x, int y) {
		// System.out.println("drawBytes: "+new String(buf, s,
		// len)+" "+graphics);
		if (!disableScreenRecording) {
			currentScreen += new String(buf, s, len);
			screenStateService.setCurrentScreen(currentScreen);
		}

		graphics.drawBytes(buf, s, len, x, y - (descent + line_space));
		if (bold)
			graphics.drawBytes(buf, s, len, x + 1, y - (descent + line_space));
		if (underline) {
			graphics.drawLine(x, y - 1, x + len * char_width, y - 1);
		}
	}

	public void drawString(String str, int x, int y) {
		// System.out.println("drawString: "+str);
		graphics.drawString(str, x, y - (descent + line_space));
		if (bold)
			graphics.drawString(str, x + 1, y - (descent + line_space));
		if (underline) {
			graphics.drawLine(x, y - 1, x + str.getBytes().length * char_width,
					y - 1);
		}
	}

	public void beep() {
		Toolkit.getDefaultToolkit().beep();
	}

	public void keyPressed(KeyEvent e) {
	}

	public void keyReleased(KeyEvent e) {
	}

	public void setSplash(Splash foo) {
		this.splash = foo;
	}

	public void setLineSpace(int foo) {
		this.line_space = foo;
	}

	public void setAntiAliasing(boolean foo) {
		antialiasing = foo;
		if (graphics == null)
			return;
		java.lang.Object mode = foo ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
				: RenderingHints.VALUE_TEXT_ANTIALIAS_OFF;
		RenderingHints hints = new RenderingHints(
				RenderingHints.KEY_TEXT_ANTIALIASING, mode);
		graphics.setRenderingHints(hints);
	}

	public void setCompression(int compression) {
		if (compression < 0 || 9 < compression)
			return;
		this.compression = compression;
	}

	public int getCompression() {
		return compression;
	}

	private java.awt.Color toColor(Object o) {
		if (o instanceof String) {
			return java.awt.Color.getColor((String) o);
		}
		if (o instanceof java.awt.Color) {
			return (java.awt.Color) o;
		}
		return Color.white;
	}

	public void setDefaultForeGround(Object f) {
		defaultfground = toColor(f);
	}

	public void setDefaultBackGround(Object f) {
		defaultbground = toColor(f);
	}

	public void setForeGround(Object f) {
		fground = toColor(f);
		graphics.setColor(getForeGround());
	}

	public void setBackGround(Object b) {
		bground = toColor(b);
	}

	private java.awt.Color getForeGround() {
		if (reverse)
			return bground;
		return fground;
	}

	private java.awt.Color getBackGround() {
		if (reverse)
			return fground;
		return bground;
	}

	public Object getColor(int index) {
		if (colors == null || index < 0 || colors.length <= index)
			return null;
		return colors[index];
	}

	public void setBold() {
		bold = true;
	}

	public void setUnderline() {
		underline = true;
	}

	public void setReverse() {
		reverse = true;
		if (graphics != null)
			graphics.setColor((java.awt.Color) getForeGround());
	}

	public void resetAllAttributes() {
		bold = false;
		underline = false;
		reverse = false;
		bground = defaultbground;
		fground = defaultfground;
		if (graphics != null)
			graphics.setColor((java.awt.Color) getForeGround());
	}

	@Override
	public void addNewLineToOutputBuffer() {
		currentScreen += "\n";

	}

	@Override
	public void sourceChanged(int arg0, Map arg1) {

	}

	@Override
	public void sourceChanged(int arg0, String arg1, Object arg2) {
		if (arg1.equals(ScreenStateSourceProvider.NAME_SELECTED)) {
			currentSelectedExpect = (List<String>) arg2;
		}
	}

	public void setRecordingEnabled(boolean recordingEnabled) {
		this.recordingEnabled = recordingEnabled;
	}
	
	public boolean isRecordingEnabled() {
		return recordingEnabled;
	}
	
	public AVCodeStateEnum getAvCodeState() {
		return avCodeState;
	}

	public void setRecordingIcon(RecordingIconAction recordingIcon) {
		this.recordingIcon = recordingIcon;		
	}
}
