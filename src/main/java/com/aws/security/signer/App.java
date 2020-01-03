/*
 * Copyright 2011-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.aws.security.signer;

import java.io.IOException;
import java.net.UnknownHostException;

import javax.crypto.Cipher;
import javax.ws.rs.core.UriBuilderException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider;
import com.aws.security.signer.log.LoggingConfigurator;

/**
 * This is a simple endpoint that hosts a signing service. 
 * 
 * More resources:
 * 	https://eclipse-ee4j.github.io/jersey.github.io/documentation/latest/user-guide.html
 *  https://github.com/yegor256/takes
 *  https://github.com/corretto/amazon-corretto-crypto-provider
 *  https://docs.oracle.com/javase/9/security/java-xml-digital-signature-api-overview-and-tutorial.htm#JSSEC-GUID-A32C5AC5-08F9-4316-9D63-0CDEAC3A5405
 *  
 *  @author lddecaro@amazon.com
 */
public class App {

	private static final Logger	logger	=	LogManager.getLogger(App.class);
	private HttpEndpoint endpoint	=	null;
	
	public String startServer() throws IOException {		
		HttpEndpoint endpoint = new HttpEndpoint();
		endpoint.startServer();
		return endpoint.getBaseURI().toString();
	}

	public String getBaseURI() throws IllegalArgumentException, UriBuilderException, UnknownHostException {
		return endpoint.getBaseURI().toString();
	}
	
	public App() {}
	
    public static void main( String[] args ) throws Exception{    	
	 	/**/
	 	LoggingConfigurator.configure();
	 	/**/
        logger.info( "AWS Signer Service Application");
        AmazonCorrettoCryptoProvider.install();
		if (Cipher.getInstance("AES/GCM/NoPadding").getProvider().getName().equals(AmazonCorrettoCryptoProvider.PROVIDER_NAME)) {
			// Successfully installed			
			logger.info("Corretto Crypto Provider Successfully installed!!");
		} else {
			logger.info("NOT installed!!");
		}
        
        App app	= new App();
        String uri = app.startServer();
        logger.info("Application started. Try accessing "+uri+"/xml/sign in a post call of your XML file");
        logger.info("Hit ^C to stop the application...");
        //JVM Shutdown Hook
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {

			    logger.info("Endpoint shutting down....");
			    Utils.logout();
			 }));
        
        Thread.currentThread().join();
    }
}
