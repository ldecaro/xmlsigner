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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.KeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.crypto.AlgorithmMethod;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.XMLStructure;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.KeyValue;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.aws.security.signer.cache.SignerInMemoryCache;
import com.cavium.key.parameter.CaviumRSAKeyGenParameterSpec;
import com.cavium.provider.CaviumProvider;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * RSA with 2048 bit key using SHA256
 * This class uses handles but it could be done creating the keys and the certificate using this example: https://docs.aws.amazon.com/cloudhsm/latest/userguide/keystore-third-party-tools.html
 * @author lddecaro
 *
 */
@Path("/xml")
@Singleton
public class XMLSigner {
	
	private static final Logger	logger	=	LogManager.getLogger(XMLSigner.class);

	private	KeyStore keyStoreHSM 			= null;
	private	Process	pr 						= null;
	private Gson gson 						= new Gson();
	private SignerInMemoryCache<String, KeyPair> cache = new SignerInMemoryCache<>(600,100,3000);
	private PasswordProtection pass			=	null;
	private final String keyStoreFile		=	"KeyStoreHSM";
	private static final String REGION		=	System.getenv("region");
	private static final String HSM_CLUSTER_ID	=	System.getenv("ClusterId");	
	private static final String RSA_SHA256_W3C_ID	=	"http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
	
	public XMLSigner() {

	        String hsmUser 				= null;
	        String hsmPassword 			= null;
	        final String hsmPartition 	= "PARTITION_1";
	        String hsmIP 				= null;

			logger.info("Using region: " + XMLSigner.REGION);
			logger.info("Using HSM cluster with Id: " + XMLSigner.HSM_CLUSTER_ID);

			// Get HSM IP address using the AWS CloudHSM API
			try {
	        	hsmIP = Utils.getHsmIP(XMLSigner.REGION, XMLSigner.HSM_CLUSTER_ID);
	        	logger.info(String.format("HSM IP is %s\n", hsmIP));
			}  catch (Exception ex) {
				logger.error("Could not get HSM IP Address...", ex);
				System.exit(0);
			}
			
			// Start the cloudhsm-client process
			try {
	            Security.addProvider(new CaviumProvider());
	            Utils.configureClientProcess(hsmIP);
	            logger.info("Starting client process...");
	            pr = Utils.startClientProcess(null);
	            logger.info("Client process started!");
	        } catch (Exception ex) {
	        		logger.fatal("Could not start HSM client process or log into HSM. Exiting...", ex);
	            System.exit(0);
	        } catch (java.lang.Error er) {
	        		logger.fatal(".so lib for Cavium not found. Did you install client?", er);
	        		logger.info("Exiting...");
	        		System.exit(0);
	        }

			// Get HSM Credentials on AWS Secrets Manager
            String HsmCredentials 	= Utils.getHsmCredentials(XMLSigner.REGION, XMLSigner.HSM_CLUSTER_ID);
			Map<String, Object> map = gson.fromJson(HsmCredentials, new TypeToken<Map<String, Object>>() {}.getType());
			hsmUser 				= map.get("HSM_USER").toString();
			hsmPassword 			= map.get("HSM_PASSWORD").toString();

	        logger.info("Logging into HSM...");
	        try{
	        	Utils.loginWithExplicitCredentials(hsmPartition, hsmUser, hsmPassword);			
	        }catch(Exception e){
	        	logger.error("Could not login into HSM. It could be the client certificate or the credentials from the SecretsManager");
	        	logger.error("Aborting run...");
	        	System.exit(0);
	        }
			logger.info("Logged in!");
			
			pass = new PasswordProtection(hsmPassword.toCharArray());
			logger.info("Loading Keystore...");
			
			keyStoreHSM	=	getKeyStoreHSM();
			logger.info("Keystore Loaded...");
		
	} 
	
	private KeyStore getKeyStoreHSM() {
		try {			
			KeyStore keyStore = KeyStore.getInstance("CloudHSM");

			try(InputStream fis = Utils.getKeyStoreFromS3(keyStoreFile, "keystore-"+XMLSigner.HSM_CLUSTER_ID, XMLSigner.REGION)){
				logger.info("Loading keystore from S3");
				keyStore.load(fis, pass.getPassword());
			}catch (Exception e) {
				logger.error("Could not load keystore file from S3");
				logger.info("Creating a new and local keystore");
				keyStore.load(null, pass.getPassword());
			}
			return keyStore;
		}catch(Exception ke) {
			System.out.println("Exception raised trying to load CloudHSM keystore. Did you install HSM client? Exception: "+ke);
			System.out.println("Exiting...");
			System.exit(0);
			return null;
		} 
	}
	
	private void persistKeyStore() {
		
        try(FileOutputStream outstream = new FileOutputStream(keyStoreFile)){
        	keyStoreHSM.store(outstream, pass.getPassword());
        	logger.info("Persisted successfully local keystore to file");
        } catch (Exception e) {
        	logger.error("Could not persist data to local keystore file. Message: "+e.getMessage());
		}
        Utils.putKeyStoreInS3(keyStoreFile, "keystore-"+XMLSigner.HSM_CLUSTER_ID, XMLSigner.REGION);
	}
    
	@POST
	@Path("/sign/{key}")
	@Consumes(MediaType.APPLICATION_XML)
	@Produces(MediaType.APPLICATION_XML)
	public String sign(String xml, @PathParam("key") String keyName){
		
		logger.info("Received this xml: " + xml);
		logger.info("Key: " + keyName);

		long init = System.currentTimeMillis();
		try {
			String returnXML = signXML(xml, keyName);
			logger.info("Signed XML in "+(System.currentTimeMillis()-init)+" ms.");
			return returnXML;
		}catch(IllegalArgumentException ie){			
			throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).entity(ie.getMessage()).build());
		}catch(Exception e) {
			throw new WebApplicationException(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Could not sign XML. Message:"+e.getMessage()).build());
		}
	}
	
	@GET
	@Path("/ping")
	@Consumes(MediaType.TEXT_PLAIN)
	@Produces(MediaType.TEXT_PLAIN)
	public Boolean ping() {
		return Boolean.TRUE;
	}
	
	@POST
	@Path("/validate")
	@Consumes(MediaType.APPLICATION_XML)
	@Produces(MediaType.TEXT_PLAIN)
	public Boolean validate(String xml) {
		
		if( xml == null || "".equals(xml.trim())){
			throw new WebApplicationException(Response.status( Response.Status.NOT_FOUND).entity("You did not sent XML file for validation.").build());
		}
		try {
			logger.info("Signed XML: "+xml);
			return validateSignedXML(xml);
		}catch(Exception e) {
			e.printStackTrace();
			throw new WebApplicationException(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Could not sign XML. Message:"+e.getMessage()).build());
		}
	}
	
	@POST
	@Path("/create/{key}")
	@Consumes(MediaType.TEXT_PLAIN)
	@Produces(MediaType.TEXT_PLAIN)
	public String createKey(@PathParam("key") String keyName, String jsonCertificate) {
		
		logger.info("Received this certificate info: " + jsonCertificate);
		logger.info("Key: "+keyName);
		if (keyName == null || "".equals(keyName)) {
			throw new WebApplicationException(Response.status( Response.Status.NOT_FOUND).entity("Label cannot be null in the url: /create/{key}").build());
		}
		try {
			Type type = new TypeToken<CertificateData>(){}.getType();
        	
			CertificateData certificateData = gson.fromJson(jsonCertificate, type);

	        KeyPair kp = generateKeyPair(2048, keyName, Boolean.TRUE);
	        logger.info("Created key pair on CloudHSM");
	
	        //
	        // Generate a certificate and associate the chain with the private key.
	        //
	        Certificate self_signed_cert = Utils.generateCert(kp, certificateData);
	        Certificate[] chain = new Certificate[1];
	        chain[0] = self_signed_cert;
	        PrivateKeyEntry entry = new PrivateKeyEntry(kp.getPrivate(), chain);
	
	        //
	        // Set the entry using the label as the alias and save the store.
	        // The alias must match the private key label.
	        // 
	        keyStoreHSM.setEntry(keyName, entry, pass);
	        
	        //persisting metadata and certificate into local keyStore.
	        //in base you want to use multiple containers this file must be shared (S3 or Parameter Store)
	        persistKeyStore();
	        
	        return "Created a key pair with the labels "+keyName+", "+keyName+":public";
	        
		}catch(IllegalArgumentException ee){
			throw new WebApplicationException(Response.status(Response.Status.NOT_FOUND).entity(ee.getMessage()).build());
		}catch(Exception e){
			logger.error(e);
			throw new WebApplicationException(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Could not create key. Message:"+e.getMessage()).build());
		}
	}
	
	@GET
	@Path("/listKeys")
	@Produces(MediaType.TEXT_PLAIN)
	public String listKeys(){
		logger.info("Listing CloudHSM keys");
		try {
			return Arrays.toString(Utils.listKeys(keyStoreHSM));
		} catch (Exception e) {
			throw new WebApplicationException(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Exception raised. Could not list keys. Reason: "+e.getMessage()).build());
		}
	}
	
	private Boolean validateSignedXML(String xml){
	
		try{
	        // Instantiate the document to be validated
	        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
	        dbf.setNamespaceAware(true);
	        Document doc = dbf.newDocumentBuilder().parse( new InputSource(new StringReader(xml)) );
	
	        // Find Signature element
	        NodeList nl =	doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
	        if (nl.getLength() == 0) {
	        		System.out.println("Cannot find Signature. XML Not Signed");
	        		return Boolean.FALSE;
	        }
	
	        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
	        DOMValidateContext valContext = new DOMValidateContext (new KeyValueKeySelector(), nl.item(0));
	        XMLSignature signature = fac.unmarshalXMLSignature(valContext);
	        
	        // Validate the XMLSignature 
	        boolean coreValidity = signature.validate(valContext);
	
	        // Check core validation status
	        if (coreValidity == false) {
	            logger.error("Signature failed core validation");
	            boolean sv = signature.getSignatureValue().validate(valContext);
	            logger.info("signature validation status: " + sv);
	            // check the validation status of each Reference
	            @SuppressWarnings("rawtypes")
				Iterator i = signature.getSignedInfo().getReferences().iterator();
	            for (int j=0; i.hasNext(); j++) {
	                boolean refValid =	((Reference) i.next()).validate(valContext);
	                logger.info("ref["+j+"] validity status: " + refValid);
	            }
	            return Boolean.FALSE;
	        } else {
	            logger.info("Signature passed core validation");
	        }
			return Boolean.TRUE;
		}catch(Exception e){
			e.printStackTrace();
			throw new WebApplicationException("Could not list keys. Message:"+e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
		}
	}
	
    /**
     * KeySelector which retrieves the public key out of the
     * KeyValue element and returns it.
     * NOTE: If the key algorithm doesn't match signature algorithm,
     * then the public key will be ignored.
     */
    private static class KeyValueKeySelector extends KeySelector {
    	
        public KeySelectorResult select(KeyInfo keyInfo,
                                        KeySelector.Purpose purpose,
                                        AlgorithmMethod method,
                                        XMLCryptoContext context) throws KeySelectorException {
            if (keyInfo == null) {
                throw new KeySelectorException("Null KeyInfo object!");
            }
            SignatureMethod sm = (SignatureMethod) method;
            @SuppressWarnings("rawtypes")
			List list = keyInfo.getContent();

            for (int i = 0; i < list.size(); i++) {
                XMLStructure xmlStructure = (XMLStructure) list.get(i);
                if (xmlStructure instanceof KeyValue) {
                    PublicKey pk = null;
                    try {
                        pk = ((KeyValue)xmlStructure).getPublicKey();
                    } catch (KeyException ke) {
                        throw new KeySelectorException(ke);
                    }
                    // make sure algorithm is compatible with method
                    if (algEquals(sm.getAlgorithm(), pk.getAlgorithm())) {
                        return new SimpleKeySelectorResult(pk);
                    }
                }
            }
            throw new KeySelectorException("No KeyValue element found!");
        }
    }
    
    static boolean algEquals(String algURI, String algName) {

        if (algName.equalsIgnoreCase("DSA") &&
            algURI.equalsIgnoreCase(SignatureMethod.DSA_SHA1)){//"http://www.w3.org/2000/09/xmldsig#dsa-sha1")) { //"http://www.w3.org/2009/xmldsig11#dsa-sha256")) {
            return true;
        } else if (algName.equalsIgnoreCase("RSA") &&
        			algURI.equalsIgnoreCase(XMLSigner.RSA_SHA256_W3C_ID)) {
            return true;
        } else {
            return false;
        }
    }
    
    private static class SimpleKeySelectorResult implements KeySelectorResult {
    	
        private PublicKey pk;
        SimpleKeySelectorResult(PublicKey pk) {
            this.pk = pk;
        }

        public Key getKey() { return pk; }
    }
	
 	private String signXML(String xml, String keyName) throws Exception {
		
		XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
		ByteArrayOutputStream stream	=	null;


		// Create a Reference to the enveloped document 
		// (in this case we are signing the whole document, so the URI of "")
		Reference ref = fac.newReference
		    ("", fac.newDigestMethod(DigestMethod.SHA256, null),
		     Collections.singletonList
		      (fac.newTransform
		        (Transform.ENVELOPED, (TransformParameterSpec) null)),
		     null, null);

		// Create the SignedInfo
			SignedInfo si = fac.newSignedInfo
			    (fac.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE_WITH_COMMENTS, (C14NMethodParameterSpec) null), 
			     fac.newSignatureMethod(XMLSigner.RSA_SHA256_W3C_ID, null),
			     Collections.singletonList(ref));
  
		KeyPair kp = null;
		if( (kp = cache.get(keyName)) == null){
			kp	=	getKeyPairFromKeyStore(keyName);
			if( kp == null){
				throw new IllegalArgumentException("Label not found: "+keyName);
			}
			cache.put(keyName, kp);
		}

		KeyInfoFactory kif = fac.getKeyInfoFactory();
		KeyValue kv = kif.newKeyValue(kp.getPublic());

		KeyInfo ki = kif.newKeyInfo(Collections.singletonList(kv));

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		
		InputStream inputStream = new ByteArrayInputStream(xml.getBytes(Charset.forName("UTF-8")));
		Document doc = dbf.newDocumentBuilder().parse(inputStream);

		// Create a DOMSignContext and specify parent element where signature will be placed in the XML
		DOMSignContext dsc = new DOMSignContext(kp.getPrivate(), doc.getDocumentElement());

		// Create the XMLSignature (but don't sign it yet)
		XMLSignature signature = fac.newXMLSignature(si, ki);

		// Sign the XML
		signature.sign(dsc);

		stream = new ByteArrayOutputStream();

		TransformerFactory tf = TransformerFactory.newInstance();
		Transformer trans = tf.newTransformer();
		trans.transform(new DOMSource(doc), new StreamResult(stream));

		return new String(stream.toByteArray());
	}
 	
 	/**
 	 * Uses the KeyStore to getKeyPair and sign content.
 	 * 
 	 * @return
 	 * @throws Exception
 	 */
 	public KeyPair getKeyPairFromKeyStore(String label) throws Exception{

 		PrivateKeyEntry keyEntry = (PrivateKeyEntry)keyStoreHSM.getEntry(label,pass);
 		if( keyEntry == null ){
 			throw new RuntimeException("The key label is not created in the HSM: "+label);
 		}
 		return new KeyPair( keyStoreHSM.getCertificate(label).getPublicKey(), keyEntry.getPrivateKey() );	
 	}
 	
 	public KeyPair getKeyPairUsingHandles(long privateHandle, long publicHandle) throws Exception {
 		return new KeyPair((PublicKey)Utils.getKeyByHandle(publicHandle), (PrivateKey)Utils.getKeyByHandle(privateHandle));
 	}
 	
    /**
     * Generate a key pair that can be used to sign.
     * Only return the private key since this is a demo and that is all we need.
     * @param keySizeInBits
     * @param keyLabel
     * @return KeyPair that is not extractable or persistent.
     */
    private KeyPair generateKeyPair(int keySizeInBits, String keyLabel, final Boolean isPersistent)
            throws InvalidAlgorithmParameterException,
            NoSuchAlgorithmException,
            NoSuchProviderException {
    	
        KeyPairGenerator keyPairGen;        	
        try {
        		// Create and configure a key pair generator
        		keyPairGen = KeyPairGenerator.getInstance("rsa", "Cavium");
        		keyPairGen.initialize(new CaviumRSAKeyGenParameterSpec(keySizeInBits, new BigInteger("65537"), keyLabel + ":public", keyLabel, false, isPersistent));
        }catch(NoSuchProviderException ne) {
        		System.out.println("It looks like HSM client is not installed or properly configured.");
        		keyPairGen	=	KeyPairGenerator.getInstance("rsa");
        		keyPairGen.initialize(keySizeInBits);
        }
        return keyPairGen.generateKeyPair();
    }
    
    public void finalize() {
    	
    		Utils.logout();
    		pr.destroyForcibly();
    }
 	
}
