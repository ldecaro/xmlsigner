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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.ws.rs.core.Application;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;
import javax.ws.rs.ext.RuntimeDelegate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.server.ResourceConfig;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

@SuppressWarnings("restriction")
/**
 * 
 * @author lddecaro@amazon.com
 *
 */
public class HttpEndpoint extends ResourceConfig {

	private static final Logger	logger	=	LogManager.getLogger(HttpEndpoint.class);
	public HttpEndpoint () {	}
	
	HttpServer startServer() throws IOException, UnknownHostException {
		
		//creates a new server listening on port 8080
		final HttpServer signerServer = HttpServer.create(new InetSocketAddress(getBaseURI().getPort()), 0);
		//create a shutdown hook to stop application when ^C is hit
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {  signerServer.stop(0); })); 
		//create a handler wrapping the application
		HttpHandler handler = RuntimeDelegate.getInstance().createEndpoint(new SignerApplication(), HttpHandler.class);
		// map handler to server root
		signerServer.createContext(getBaseURI().getPath(), handler);
		//start the server
		signerServer.start();
		
		return signerServer;
	}
	
	private int getPort(int defaultPort) {
		
		int portInUse	=	defaultPort;
		final String port = System.getProperty("jersey.config.test.container.port");
		if( port != null ) {
			try {
				portInUse	=	Integer.parseInt(port);
			}catch(NumberFormatException nfe) {
				logger.info("Value of jersey.config.test.container.port property is not a valid positive integer ["+port+"]. Reverting to default ["+defaultPort+"].");
			}
		}
		return portInUse;
	}
	
	public URI getBaseURI() throws IllegalArgumentException, UriBuilderException, UnknownHostException   {
		
		return UriBuilder.fromUri("http://"+InetAddress.getLocalHost().getHostName()+"/").port(getPort(8080)).build();
	}
	
	private class SignerApplication extends Application {
		
		public Set<Class<?>> getClasses() {			
			HashSet<Class<?>>set = new HashSet<>();
			set.add((XMLSigner.class));
			return (Set<Class<?>>)Collections.unmodifiableSet( set );
		}
	}
}
