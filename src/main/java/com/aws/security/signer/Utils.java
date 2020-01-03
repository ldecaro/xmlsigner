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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.regions.Regions;
// Imports for AWS CloudHSM
import com.amazonaws.services.cloudhsmv2.AWSCloudHSMV2;
import com.amazonaws.services.cloudhsmv2.AWSCloudHSMV2ClientBuilder;
import com.amazonaws.services.cloudhsmv2.model.DescribeClustersRequest;
import com.amazonaws.services.cloudhsmv2.model.DescribeClustersResult;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
// Imports for AWS Secrets Manager
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.amazonaws.services.secretsmanager.model.GetSecretValueRequest;
import com.amazonaws.services.secretsmanager.model.GetSecretValueResult;
import com.amazonaws.services.secretsmanager.model.InvalidParameterException;
import com.amazonaws.services.secretsmanager.model.InvalidRequestException;
import com.amazonaws.services.secretsmanager.model.ResourceNotFoundException;
import com.cavium.asn1.Encoder;
import com.cavium.cfm2.CFM2Exception;
import com.cavium.cfm2.LoginManager;
import com.cavium.cfm2.Util;
import com.cavium.key.CaviumAESKey;
import com.cavium.key.CaviumECPrivateKey;
import com.cavium.key.CaviumECPublicKey;
import com.cavium.key.CaviumKey;
import com.cavium.key.CaviumKeyAttributes;
import com.cavium.key.CaviumRSAPrivateKey;
import com.cavium.key.CaviumRSAPublicKey;

@SuppressWarnings("deprecation")
/**
 * Utility Class to manage Cloud HSM client configuration and start client process. 
 * This class also contains utility methods to login/logout and display key info.
 * 
 * @author lddecaro@amazon.com
 */
public class Utils {
	
	private static final Logger	logger	=	LogManager.getLogger(Utils.class);
    
    /**
     * Get HSM IP Address by querying AWS HSM Cluster API
     * @param region AWS Region where the Cluster is created
     * @param HsmClusterId Id of the HSM Cluster
     * @return String HSM Ip Address
     */
    public static String getHsmIP(String region, String HsmClusterId) {
        String HsmIp = null;
        
    	AWSCloudHSMV2 client = AWSCloudHSMV2ClientBuilder.standard()
                                    .withRegion(region)
                                    .build();
                                    
        DescribeClustersResult result = null;
		try {
	        result = client.describeClusters(new DescribeClustersRequest()
    	        .addFiltersEntry("clusterIds", Collections.singletonList(HsmClusterId)));
		} catch (Exception ex) {
	            logger.error("Couldn't get HSM Cluster information...",ex);
	            System.exit(0);
    	}
    	
    	HsmIp = result.getClusters().get(0).getHsms().get(0).getEniIp();
        return HsmIp;
    }

    /**
     * Get HSM credentials from AWS Secrets Manager
     * @param region AWS Region where the Cluster is created
     * @param HsmClusterId Id of the HSM Cluster
     * @return String HSM JSON object with credentials
     */
    public static String getHsmCredentials(String region, String HsmClusterId) {
        String secretName = "CloudHSM/" + HsmClusterId + "/credentials";
        
        AWSSecretsManager client = AWSSecretsManagerClientBuilder.standard()
                                    .withRegion(region)
                                    .build();

        String secret, decodedBinarySecret;
        GetSecretValueRequest getSecretValueRequest = new GetSecretValueRequest()
              .withSecretId(secretName);
        GetSecretValueResult getSecretValueResult = null;

        try {
            getSecretValueResult = client.getSecretValue(getSecretValueRequest);
        } catch (ResourceNotFoundException e) {
            logger.error("The requested secret " + secretName + " was not found");
        } catch (InvalidRequestException e) {
            logger.error("The request was invalid due to: " + e.getMessage());
        } catch (InvalidParameterException e) {
            logger.error("The request had invalid params: " + e.getMessage());
        }

        if (getSecretValueResult == null) {
            return null;
        }

        // Depending on whether the secret was a string or binary, one of these fields will be populated
        if (getSecretValueResult.getSecretString() != null) {
            secret = getSecretValueResult.getSecretString();
            return secret;
        }
        else {
            decodedBinarySecret = new String(Base64.getDecoder().decode(getSecretValueResult.getSecretBinary()).array());
            return decodedBinarySecret;
        }
    }
    
    /**
     * The explicit login method allows users to pass credentials to the Cluster manually. If you obtain credentials
     * from a provider during runtime, this method allows you to login.
     * @param user Name of CU user in HSM
     * @param pass Password for CU user.
     * @param partition HSM ID
     */
    static void loginWithExplicitCredentials(String HsmPartition, String HsmUser, String HsmPassword) throws Exception {
    	
        LoginManager lm = LoginManager.getInstance();
        try {
            lm.login(HsmPartition, HsmUser, HsmPassword);
            logger.info("Login successful in HSM");
        } catch (CFM2Exception e) {
            if (CFM2Exception.isAuthenticationFailure(e)) {
                logger.error("Detected invalid credentials to HSM. Could not login");
            }
            logger.error(e);
            throw e;
        }
    }
    
	/**
	 * Logout will force the LoginManager to end your session.
	 * Associate the ShutdownHook to the logout method
	 */
	public static void logout() {
		try {
			LoginManager.getInstance().logout();
			logger.info("Logged out from HSM");
			logger.info("Destroying HSM client process");
			logger.info("HSMClient Process Destroyed");
		
		} catch (CFM2Exception e) {
			logger.error(e);
		}
	}
	
	/** 
	 * Start the CloudHSM client process
	 * @param confFile
	 * @return Process referring to the client process
	 */
	static Process startClientProcess(String configFile) throws Exception {

		if( configFile == null || "".equals(configFile.trim())) {
			configFile = "/opt/cloudhsm/etc/cloudhsm_client.cfg";
		}
		logger.info("Starting CloudHSM client ... ");

		File logFile = new File("/tmp/client.log");
		Process pr = new ProcessBuilder("/opt/cloudhsm/bin/cloudhsm_client",configFile).redirectErrorStream(true).redirectOutput(logFile).start();

		// Wait for the client to start
		logger.info("Waiting for cloudhsm client to start ... ");
		Pattern pat = Pattern.compile("libevmulti_init: Ready !");

		FileReader lf = new FileReader(logFile);
		try(BufferedReader in = new BufferedReader(lf)){

			String line="";
			Matcher match = pat.matcher(line);
			while (! match.find()) {
				line=in.readLine();
				if (line!=null) {
					match = pat.matcher(line);
				}
			}
		}
		logger.info("CloudHSM client started ... ");
		return pr;
	}
	
	/**
	 * Configures local HSM client using the HSM IP from file HsmCredentials.properties 
	 * @param hsmIP
	 * @throws Exception
	 */
	static void configureClientProcess(String hsmIP) throws Exception {
		
		logger.info("Configuring CloudHSM client process so that this container uses HSM with IP "+hsmIP);
		File logFile = new File("/tmp/client_configuration.log");
		ProcessBuilder builder = new ProcessBuilder();
		Process pr = builder.command("bash", "-c", "/opt/cloudhsm/bin/configure -a " + hsmIP).redirectErrorStream(true).redirectOutput(logFile).start();
		// Wait for the client to start
		Pattern pat = Pattern.compile("cloudhsm_mgmt_util.cfg");

		FileReader lf = new FileReader(logFile);
		try(BufferedReader in = new BufferedReader(lf)){

			String line="";
			Matcher match = pat.matcher(line);
			while (! match.find()) {
				line=in.readLine();
				if (line!=null) {
					match = pat.matcher(line);
				}
			}
		}
		logger.info("Client Process Configured... ");
		if (pr.isAlive()) {
			pr.destroy();
		}
	}
	
	static InputStream getKeyStoreFromS3(final String keyName, final String bucketName, final String region) throws Exception{
		
		logger.info(String.format("Downloading %s from S3 bucket %s...\n", keyName, bucketName));
		final AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRegion(Regions.fromName(region)).build();
	    return s3.getObject(bucketName, keyName).getObjectContent();
		
	}
	
	static void putKeyStoreInS3(final String keyName, final String bucketName, final String region){
		
		logger.info(String.format("Uploading local keystore to S3..."));
		try{
			final AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRegion(Regions.fromName(region)).build();
			s3.putObject(bucketName, keyName, new File(keyName));
			logger.info("KeyStore file successfully uploaded to S3");
		}catch(Exception e){
			logger.error("Could not upload keystore file to S3. Message:"+e.getMessage()+"Bucket: "+bucketName+", keyName: "+keyName);
		}
	}
	
    /**
     * Generate a certificate signed by a given keypair.
     */
    static Certificate generateCert(KeyPair kp, CertificateData certData) throws CertificateException {
    	
        final  byte[] COMMON_NAME_OID = new byte[] { (byte) 0x55, (byte) 0x04, (byte) 0x03 };
        final  byte[] COUNTRY_NAME_OID = new byte[] { (byte) 0x55, (byte) 0x04, (byte) 0x06 };
        final  byte[] LOCALITY_NAME_OID = new byte[] { (byte) 0x55, (byte) 0x04, (byte) 0x07 };
        final  byte[] STATE_OR_PROVINCE_NAME_OID = new byte[] { (byte) 0x55, (byte) 0x04, (byte) 0x08 };
        final  byte[] ORGANIZATION_NAME_OID = new byte[] { (byte) 0x55, (byte) 0x04, (byte) 0x0A };
        final  byte[] ORGANIZATION_UNIT_OID = new byte[] { (byte) 0x55, (byte) 0x04, (byte) 0x0B };
    	
        CertificateFactory cf = CertificateFactory.getInstance("X509");
        PublicKey publicKey = kp.getPublic();
        PrivateKey privateKey = kp.getPrivate();
        byte[] version = Encoder.encodeConstructed((byte) 0, Encoder.encodePositiveBigInteger(new BigInteger("2"))); // version 1
        byte[] serialNo = Encoder.encodePositiveBigInteger(new BigInteger(1, Util.computeKCV(publicKey.getEncoded())));

        // Use the SHA512 OID and algorithm.
        byte[] signatureOid = new byte[] {
            (byte) 0x2A, (byte) 0x86, (byte) 0x48, (byte) 0x86, (byte) 0xF7, (byte) 0x0D, (byte) 0x01, (byte) 0x01, (byte) 0x0D };
        String sigAlgoName = "SHA512WithRSA";

         byte[] signatureId = Encoder.encodeSequence(
                                         Encoder.encodeOid(signatureOid),
                                         Encoder.encodeNull());

         byte[] issuer = Encoder.encodeSequence(
                                     encodeName(COUNTRY_NAME_OID, certData.getCountry()),
                                     encodeName(STATE_OR_PROVINCE_NAME_OID, certData.getState()),
                                     encodeName(LOCALITY_NAME_OID, certData.getCity()),
                                     encodeName(ORGANIZATION_NAME_OID, certData.getOrganizationName()),
                                     encodeName(ORGANIZATION_UNIT_OID, certData.getOrganizationUnit()),
                                     encodeName(COMMON_NAME_OID, certData.getCommonName())
                                 );

         Calendar c = Calendar.getInstance();
         c.add(Calendar.DAY_OF_YEAR, -1);
         Date notBefore = c.getTime();
         c.add(Calendar.YEAR, 1);
         Date notAfter = c.getTime();
         byte[] validity = Encoder.encodeSequence(
                                         Encoder.encodeUTCTime(notBefore),
                                         Encoder.encodeUTCTime(notAfter)
                                     );
         byte[] key = publicKey.getEncoded();

         byte[] certificate = Encoder.encodeSequence(
                                         version,
                                         serialNo,
                                         signatureId,
                                         issuer,
                                         validity,
                                         issuer,
                                         key);
         Signature sig;
         byte[] signature = null;
         try {
             sig = Signature.getInstance(sigAlgoName, "Cavium");
             sig.initSign(privateKey);
             sig.update(certificate);
             signature = Encoder.encodeBitstring(sig.sign());

         } catch (Exception e) {
             logger.error(e.getMessage());
             return null;
         }

         byte [] x509 = Encoder.encodeSequence(
                         certificate,
                         signatureId,
                         signature
                         );
         return cf.generateCertificate(new ByteArrayInputStream(x509));
    }

     //
     // Simple OID encoder.
     // Encode a value with OID in ASN.1 format
     //
     private static byte[] encodeName(byte[] nameOid, String value) {
         byte[] name = null;
         name = Encoder.encodeSet(
                     Encoder.encodeSequence(
                             Encoder.encodeOid(nameOid),
                             Encoder.encodePrintableString(value)
                     )
                 );
         return name;
     }

    //
    // List all the keys in the keystore.
    //
    static String[] listKeys(KeyStore keystore) throws Exception {

    	Collection<String>aliases	=	new ArrayList<>();
        for(Enumeration<String> entry = keystore.aliases(); entry.hasMoreElements();) {
            aliases.add(entry.nextElement());
        }
        return aliases.toArray(new String[]{});
    }
	
    /**
     * Retrieves an existing key from the HSM using a key handle.
     * @param handle The key handle in the HSM.
     * @return CaviumKey object or null in case key type is not one of AWS, RSA, EC or Generic Secret
     */
    static CaviumKey getKeyByHandle(long handle) throws CFM2Exception {

    		// Load key using key attributes
        byte[] keyAttribute = Util.getKeyAttributes(handle);
        CaviumKeyAttributes cka = new CaviumKeyAttributes(keyAttribute);

        if(CaviumKeyAttributes.KEY_TYPE_AES == cka.getKeyType() ) {
            CaviumAESKey aesKey = new CaviumAESKey(handle, cka);
            return aesKey;
        }
        else if(CaviumKeyAttributes.KEY_TYPE_RSA == cka.getKeyType() && 
        		CaviumKeyAttributes.CLASS_PRIVATE_KEY == cka.getKeyClass()) {
        	
            CaviumRSAPrivateKey privKey = new CaviumRSAPrivateKey(handle, cka);
            return privKey;
        }
        else if(CaviumKeyAttributes.KEY_TYPE_RSA == cka.getKeyType() && 
        		CaviumKeyAttributes.CLASS_PUBLIC_KEY == cka.getKeyClass()) {
        	
            CaviumRSAPublicKey pubKey = new CaviumRSAPublicKey(handle, cka);
            return pubKey;
        }
        else if(CaviumKeyAttributes.KEY_TYPE_EC == cka.getKeyType() && 
        		CaviumKeyAttributes.CLASS_PRIVATE_KEY == cka.getKeyClass()) {
        	
            CaviumECPrivateKey privKey = new CaviumECPrivateKey(handle, cka);
            return privKey;
        }
        else if(CaviumKeyAttributes.KEY_TYPE_EC == cka.getKeyType() && 
        		CaviumKeyAttributes.CLASS_PUBLIC_KEY == cka.getKeyClass()) {
        	
            CaviumECPublicKey pubKey = new CaviumECPublicKey(handle, cka);
            return pubKey;
        }
        else if(CaviumKeyAttributes.KEY_TYPE_GENERIC_SECRET == cka.getKeyType()) {
        	
            CaviumKey key = new CaviumAESKey(handle, cka);
            return key;
        }
        return null;
    }
}
