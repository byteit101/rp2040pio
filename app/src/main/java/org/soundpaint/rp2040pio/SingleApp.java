/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package org.soundpaint.rp2040pio;

import java.util.Arrays;
import java.util.HashMap;

import org.soundpaint.rp2040pio.doctool.RegistersDocsBuilder;
import org.soundpaint.rp2040pio.monitor.Monitor;
import org.soundpaint.rp2040pio.observer.Observer;
import org.soundpaint.rp2040pio.observer.code.CodeObserver;
import org.soundpaint.rp2040pio.observer.diagram.Diagram;
import org.soundpaint.rp2040pio.observer.fifo.FifoObserver;
import org.soundpaint.rp2040pio.observer.gpio.GPIOObserver;
import org.soundpaint.rp2040pio.observer.multigui.MultiGuiObserver;

public class SingleApp {

    public static void main(String[] args) throws NoSuchMethodException, SecurityException, ReflectiveOperationException, IllegalArgumentException {
        var map = new HashMap<String, Class<?>>();
        map.put("server", EmulationServer.class);
        map.put("monitor", Monitor.class);
        map.put("fifoobserver", FifoObserver.class);
        map.put("codeobserver", CodeObserver.class);
        map.put("gpioobserver", GPIOObserver.class);
        map.put("diagram", Diagram.class);
        map.put("doctool", RegistersDocsBuilder.class);
        map.put("observer", Observer.class);
        map.put("gui", MultiGuiObserver.class);

        if (args.length < 1)
        {
        	System.err.println("Tools: ");
        	map.forEach((k,v) -> {
        		System.err.println("    " + k);
        	});
        }
        else
        {
        	String[] rest = Arrays.copyOfRange(args, 1, args.length);
        	var dest = map.get(args[0]);
        	if (dest == null)
        		System.out.println("Invalid tool name");
        	else
        	{
        		dest.getMethod("main",String[].class).invoke(null, (Object)rest);
        	}
        }
    }
}