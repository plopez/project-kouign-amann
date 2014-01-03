import java.io.IOException;

import com.pi4j.io.i2c.I2CBus;
import com.pi4j.io.i2c.I2CDevice;
import com.pi4j.io.i2c.I2CFactory;


public class AdafruitLcdPlate {
	static final int MCP23017_IOCON_BANK0 = 0x0A;  // IOCON when Bank 0 active
	static final int MCP23017_IOCON_BANK1 = 0x15;  // IOCON when Bank 1 active

	// These are register addresses when in Bank 1 only:
	static final int MCP23017_GPIOA = 0x09;
	static final int MCP23017_GPIOB = 0x19;
	static final int MCP23017_IODIRB = 0x10;

	static final int[] SHIFT_REVERSE = [
		0x00, 0x10, 0x08, 0x18,
		0x04, 0x14, 0x0C, 0x1C,
		0x02, 0x12, 0x0A, 0x1A,
		0x06, 0x16, 0x0E, 0x1E
	];

	// LCD Commands
	public static final int LCD_CLEARDISPLAY = 0x01;
	public static final int LCD_RETURNHOME = 0x02;
	public static final int LCD_ENTRYMODESET = 0x04;
	public static final int LCD_DISPLAYCONTROL = 0x08;
	public static final int LCD_CURSORSHIFT   = 0x10;
	public static final int LCD_FUNCTIONSET = 0x20;
	public static final int LCD_SETCGRAMADDR = 0x40;
	public static final int LCD_SETDDRAMADDR = 0x80;

	// flags for display on/off control
	public static final int  LCD_DISPLAYON = 0x04;
	public static final int LCD_DISPLAYOFF = 0x00;
	public static final int LCD_CURSORON = 0x02;
	public static final int LCD_CURSOROFF = 0x00;
	public static final int LCD_BLINKON = 0x01;
	public static final int LCD_BLINKOFF = 0x00;

	// flags for display entry mode
	static final int LCD_ENTRYRIGHT = 0x00;
	static final int LCD_ENTRYLEFT = 0x02;
	static final int LCD_ENTRYSHIFTINCREMENT = 0x01;
	static final int LCD_ENTRYSHIFTDECREMENT = 0x00;

	// flags for display/cursor shift
	static final int LCD_DISPLAYMOVE = 0x08;
	static final int LCD_CURSORMOVE = 0x00;
	static final int LCD_MOVERIGHT= 0x04;
	static final int LCD_MOVELEFT = 0x00;

	// I2C is relatively slow.  MCP output port states are cached
	// so we don't need to constantly poll-and-change bit states.
	private int portA = 0x00;
	private int portB = 0x00;
	private int ioDirB = 0x10;

	private int displayShift =  LCD_CURSORMOVE | LCD_MOVERIGHT;
	private int displayMode =  LCD_ENTRYLEFT | LCD_ENTRYSHIFTDECREMENT;
	private int displayControl = LCD_DISPLAYON | LCD_CURSOROFF | LCD_BLINKOFF;

	protected int[] rowOffsets = [0x00, 0x40, 0x14, 0x54];


	protected int rows;
	protected int columns;
	private I2CDevice device;

	public AdafruitLcdPlate(int busNo, int address) throws IOException, InterruptedException
	{
		this.columns = 16;
		this.rows = 2;
		
		I2CBus i2cBus = I2CFactory.getInstance(busNo);
		device = i2cBus.getDevice(address);

		// Set MCP23017 IOCON register to Bank 0 with sequential operation.
		// If chip is already set for Bank 0, this will just write to OLATB,
		// which won't seriously bother anything on the plate right now
		// (blue backlight LED will come on, but that's done in the next
		// step anyway).
		device.write(MCP23017_IOCON_BANK1, (byte) 0);

		// Brute force reload ALL registers to known state.  This also
		// sets up all the input pins, pull-ups, etc. for the Pi Plate.
		byte [] piPlateSetupRegisters  = [  
				0x3F,   			// IODIRA    R+G LEDs=outputs, buttons=inputs
				(byte) ioDirB ,   	// IODIRB    LCD D7=input, Blue LED=output
				0x3F,   			// IPOLA     Invert polarity on button inputs
				0x00,   			// IPOLB
				0x00,   			// GPINTENA  Disable interrupt-on-change
				0x00,   			// GPINTENB
				0x00,   			// DEFVALA
				0x00,   			// DEFVALB
				0x00,   			// INTCONA
				0x00,   			// INTCONB
				0x00,   			// IOCON
				0x00,   			// IOCON
				0x3F,   			// GPPUA     Enable pull-ups on buttons
				0x00,   			// GPPUB
				0x00,   			// INTFA
				0x00,   			// INTFB
				0x00,   			// INTCAPA
				0x00,   			// INTCAPB
				(byte) portA,   	// GPIOA
				(byte) portB,   	// GPIOB
				(byte) portA,   	// OLATA     0 on all outputs; side effect of
				(byte) portB  		// OLATB     turning on R+G+B backlight LEDs.
		];
		device.write(0, piPlateSetupRegisters, 0, piPlateSetupRegisters.length);

		// Switch to Bank 1 and disable sequential operation.
		// From this point forward, the register addresses do NOT match
		// the list immediately above.  Instead, use the constants defined
		// at the start of the class.  Also, the address register will no
		// longer increment automatically after this -- multi-byte
		// operations must be broken down into single-byte calls.
		device.write(MCP23017_IOCON_BANK0, (byte) 0xA0);

		writeCmd(0x33); // Init
		writeCmd(0x32); // Init
		writeCmd(0x28); // 2 line 5x8 matrix
		writeCmd(LCD_CLEARDISPLAY);
		writeCmd(LCD_CURSORSHIFT | displayShift);
		writeCmd(LCD_ENTRYMODESET | displayMode);
		writeCmd(LCD_DISPLAYCONTROL | displayControl);
		writeCmd(LCD_RETURNHOME);
	}

	public int getRowCount() {
		return rows;
	}

	public int getColumnCount() {
		return columns;
	}

	public void clear()
	{
		writeCmd(LCD_CLEARDISPLAY);
	}

	public void setCursorHome()
	{
		writeCmd(LCD_RETURNHOME);
	}

	public void setCursorPosition(int row, int column)
	{
		writeCmd(LCD_SETDDRAMADDR | (column + rowOffsets[row]));
	}

	public void write(byte data)
	{
		try {
			pollWait();
			int bitmask = portB & 0x01;   // Mask out PORTB LCD control bits
			bitmask |= 0x80; // Set data bit
	
			byte[] bytes = ShiftAndMap4(bitmask, data);
			device.write(MCP23017_GPIOB, bytes, 0, 4);
			portB = bytes[3];
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void writeCmd(int cmd)
	{
		try {
			pollWait();
			int bitmask = portB & 0x01;   // Mask out PORTB LCD control bits
	
			byte[] data = ShiftAndMap4(bitmask, cmd);
			device.write(MCP23017_GPIOB, data, 0, 4);
			portB = data[3];
	
			// If a poll-worthy instruction was issued, reconfigure D7
			// pin as input to indicate need for polling on next call.
			if (cmd == LCD_CLEARDISPLAY || cmd == LCD_RETURNHOME) {
				ioDirB |= 0x10;
				device.write(MCP23017_IODIRB, (byte) ioDirB);
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void write(int row, String string)
	{
		setCursorPosition(row, 0);
		write(string);
	}

	public void write(String s)
	{
		int sLen = s.length();
		int bytesLen = 4 * sLen;
		if (sLen < 1) return;
		
		try {
		
			pollWait();
			int bitmask = portB & 0x01;   // Mask out PORTB LCD control bits
			bitmask |= 0x80; // Set data bit
	
			byte[] bytes = new byte[4 * sLen];
			for(int i=0; i<sLen; i++) {
				byte[] data = ShiftAndMap4(bitmask, (byte) s.charAt(i));
				for (int j=0; j<4; j++) {
					bytes[(i * 4) + j] = data[j];
				}
			}
			device.write(MCP23017_GPIOB, bytes, 0, bytesLen);
			portB = bytes[bytesLen - 1];
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	protected void pollWait() throws IOException
	{
		// The speed of LCD accesses is inherently limited by I2C through the
		// port expander.  A 'well behaved program' is expected to poll the
		// LCD to know that a prior instruction completed.  But the timing of
		// most instructions is a known uniform 37 mS.  The enable strobe
		// can't even be twiddled that fast through I2C, so it's a safe bet
		// with these instructions to not waste time polling (which requires
		// several I2C transfers for reconfiguring the port direction).
		// The D7 pin is set as input when a potentially time-consuming
		// instruction has been issued (e.g. screen clear), as well as on
		// startup, and polling will then occur before more commands or data
		// are issued.

		// If pin D7 is in input state, poll LCD busy flag until clear.
		if ((ioDirB & 0x10) != 0) {
			int lo = (portB & 0x01) | 0x40;
			int hi = lo | 0x20; // E=1 (strobe)
			device.write(MCP23017_GPIOB, (byte) lo);
			while (true) {
				device.write((byte) hi); // Strobe high (enable)
				int bits = device.read(); // First nybble contains busy state
			        byte[] array = [(byte) lo, (byte) hi, (byte) lo];	
				device.write(MCP23017_GPIOB, array, 0, 3); // Strobe low, high, low.  Second nybble (A3) is ignored.
				if ((bits & 0x02) == 0) break; // D7=0, not busy
			}
			portB = lo;
			ioDirB &= 0xEF; // Polling complete, change D7 pin to output
			device.write(MCP23017_IODIRB, (byte) ioDirB);
		}
	}

	protected byte[] ShiftAndMap4(int bitmask, int value)
	{
		// The LCD data pins (D4-D7) connect to MCP pins 12-9 (PORTB4-1), in
		// that order.  Because this sequence is 'reversed,' a direct shift
		// won't work.  This table remaps 4-bit data values to MCP PORTB
		// outputs, incorporating both the reverse and shift.
		int hi = bitmask | SHIFT_REVERSE[value >> 4];
		int lo = bitmask | SHIFT_REVERSE[value & 0x0F];

		byte[] data = [ (byte) (hi | 0x20),  (byte) hi,  (byte) (lo | 0x20),  (byte) lo];

		return data;
	}

	public void setCursor(boolean on) throws IOException
	{
		if (on) {
			displayControl |= LCD_CURSORON;
		} else {
			displayControl &= ~LCD_CURSORON;
		}
		writeCmd(LCD_DISPLAYCONTROL | displayControl);
	}

	public void setDisplay(boolean on)
	{
		if (on) {
			displayControl |= LCD_DISPLAYON ;
		} else {
			displayControl &= ~LCD_DISPLAYON;
		}
		writeCmd(LCD_DISPLAYCONTROL | displayControl);
	}

	public void setBacklight(int  color)
	{
		int c = ~color;
		portA = (byte) ((portA & 0x3F) | ((c & 0x03) << 6));
		portB = (byte) ((portB & 0xFE) | ((c & 0x04) >> 2));
		// Has to be done as two writes because sequential operation is off.
		try {
			device.write(MCP23017_GPIOA, (byte) portA);
			device.write(MCP23017_GPIOB, (byte) portB);
		} catch (IOException ex) {
			// TODO Auto-generated catch block
			ex.printStackTrace();
		}
	}

	public void shutdown()
	{
		clear();
		setDisplay(false);
		setBacklight(0x00);
		
	}

  byte[] _button_pins = [0, 1, 2, 3, 4];
  
  private void writa(byte b) throws IOException {
    device.write(b);
    //System.out.println("W:" + (int)b);
  }
  
  private byte[] reada(int len) throws IOException {
    byte[] b = new byte[len];
    device.read(b, 0, len);
    //for(int i=0; i<len; i++)
    	//System.out.println("R:" + (int)b[i]);
    return b;
  }
  
  private byte reada() throws IOException {
    byte[] b = reada(1);
    return b[0];
  }
  
  byte digitalRead(byte p) throws IOException {
    byte gpioaddr;
    // only 16 bits!
    if (p > 15)
      return 0;
    if (p < 8) {
      gpioaddr = MCP23017_GPIOA;
    }
    else {
      gpioaddr = MCP23017_GPIOB;
      p -= 8;
    }
    // read the current GPIO
    writa(gpioaddr); 
    byte rec = reada();
    byte step1 = (byte)(rec >> p);
    return (byte)(step1 & (byte)0x1);
  }
  
  public byte readButtons() throws IOException {
    byte reply = 0x1F;
    for (byte i=0; i<5; i++) {
	reply &= ~((digitalRead(_button_pins[i])) << i);
    }
    return reply;
    
  }

  public int[] readButtonsPressed() throws IOException {
    int[] result = [0,0,0,0,0]
    for (byte i=0; i<5; i++) {
       result[i] = (int) digitalRead(_button_pins[i]) 
    }
    return result;
  }
}

