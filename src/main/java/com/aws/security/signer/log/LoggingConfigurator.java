package com.aws.security.signer.log;

import java.net.URISyntaxException;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.io.IoBuilder;

/** 
 * @author lddecaro@amazon.com
 */
public class LoggingConfigurator {
		
	
	private LoggingConfigurator() {}
	
	public static final void configure() {
		
		/*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*/
        LoggerContext context = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
        // this will force a reconfiguration
        try {
			context.setConfigLocation( Thread.currentThread().getContextClassLoader().getResource("com/aws/security/signer/log/log4j2.xml").toURI() );
		} catch (URISyntaxException e) {
			System.out.println("Cannot start logging framework: Log4j2. Configuration File Not Found. URISyntaxException:"+e.getMessage());
			e.printStackTrace();
		}
        context.reconfigure();
        context.start();
        context.updateLoggers();
		/*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*/
		
		// make sure everything sent to System.err is logged
		 System.setErr( IoBuilder.forLogger( LogManager.getRootLogger()).setLevel(Level.ERROR).buildPrintStream() );
		 // make sure everything sent to System.out is also logged
		 System.setOut( IoBuilder.forLogger( LogManager.getRootLogger()).setLevel(Level.INFO).buildPrintStream() );
		 
		 System.out.println("Just configured log4j2 and redirected System.out and System.err messages to RootLogger.");
		 Logger	logger	=	LogManager.getLogger(LoggingConfigurator.class);
		 logger.info("Log4j2 is set.");
	}
}
