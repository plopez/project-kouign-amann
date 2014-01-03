/*
  On a virgin raspbian install:

  sudo apt-get update
  sudo apt-get upgrade
  sudo apt-get install libpcsclite1 pcscd emacs

  curl -s get.gvmtool.net | bash
  source "$HOME/.gvm/bin/gvm-init.sh"

  gvm install groovy

  add user 'pi' to group 'lp'
  WARN: this modifies the file in-place
  sed -E 's/^(lp:.*)$/\1pi/g' -i /etc/group

  reload your profile (eg disconnect and reconnect eventually)

  groovy -Dsun.security.smartcardio.library=/usr/lib/arm-linux-gnueabihf/libpcsclite.so.1 <this script>
*/
@Grapes( 
@Grab(group='com.pi4j', module='pi4j-gpio-extension', version='0.0.5') 
)

import javax.smartcardio.Card
import javax.smartcardio.CardTerminals
import javax.smartcardio.CommandAPDU
import javax.smartcardio.ResponseAPDU
import javax.smartcardio.TerminalFactory

String readCard() {
  def terminals = TerminalFactory.getDefault().terminals().list()
  println "found ${terminals.size()} terminals"
  def terminal = terminals [0]
  println "Reading card UID, timeout in ${3000} ms"

  terminal.waitForCardPresent(3000)

  Card card = terminal.connect("*")
  ResponseAPDU cardResponse = card.basicChannel.transmit(new CommandAPDU([0xFF, 0xCA, 0x00, 0x00, 0x00] as byte[]))
  card.disconnect(false)
  byteArrayToNormalizedString(cardResponse)    
}

private String byteArrayToNormalizedString(ResponseAPDU cardReponse) {
  cardReponse.bytes.encodeHex().toString()
  .toUpperCase()
  .replaceAll('(..)', '$0 ')
  .trim()
  .substring(0, 12)
}

String run(String command) {

  def dev = new File('/dev/usb/lp0')

  dev.withPrintWriter { writer ->
    writer.append('\033'+command+'\015')
  }

  readResponse()
}

String readResponse() {

  String res = ""
  new File('/dev/char/180:0').withReader { Reader reader ->
    int charRead = reader.read()

    while (charRead != 0) {

      if (charRead != -1) {
	res += charRead as char
      }
      charRead = reader.read()
    }
  }
  res
}


5.times {
  println 'Starting'
  println 'Enabling verbose mode: ' + run('Pps;0')
  println 'Loading card: ' + run('Sis')
  println 'RFID read: ' + readCard()
  println 'Ejecting card: ' + run('Sie')
}

