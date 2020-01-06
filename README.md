![workshop logo](https://github.com/ldecaro/xmlsigner/blob/master/images/hsm-fargate.png)

# Signer Microservice - Digital Invoice Signing

Em [Português](README_pt_BR.md)

A microservice for signing digital invoices created using the XML format. This microservice is also able to digitaly sign any XML document. In payment industries, more specifically in Brazil, law requires all invoices to be created in digital format (XML) and to be digitally signed before sent to customers. An example is the NF-e or Nota Fiscal Eletrônica.

Signer creates a microservice on AWS using a Serverless Container architecture enabling you to, via IaaC, create and deploy a microservice that leverages the use of HTTP protocol to sign any file in XML format.

The XML Signature process uses a Cloud Hardware Secure Module ([AWS CloudHSM](https://aws.amazon.com/pt/cloudhsm/)) provisioned and configured in the Cloud. The microservice is written in Java using Amazon Corretto and cryptography process is enhanced with the use of the [Amazon Corretto Crypto Provider (ACCP)](https://aws.amazon.com/blogs/opensource/introducing-amazon-corretto-crypto-provider-accp/).

Care about performance? If we consider p99 requests the Fargate Container deployed with this project can sign a 6KB document in around 15ms (Tested with a real invoice from Brazil - NF-e)

## Proposed Architecture

This project includes the AWS CloudFormation scripts to create, configure, build and deploy the following infrastructure:
  * Network
  * CloudHSM  
  * Java Container for XML signature process
  * [ECS Fargate Service](https://aws.amazon.com/pt/fargate/)


The proposed architecture is:

![proposed solution](images/architecture.png)

## Quick links

1. [Installation](#Installation)
2. [Using the Signer Container](#Using-the-container)
3. [Troubleshoot](#Troubleshoot)
4. [Cleanup](#Cleanup)

## Installation

You can have your microservice deployed and running in three automatic steps:
Note: Total time for this setup is around 40 minutes. Cost of this infrastructure for testing purposes is around US$ 3.50 per hour (us-east-1 / North Virginia). **You need a region where service CodeBuild is running**

### Deploy Network and CloudHSM
  
|Deploy | Region |
|:---:|:---:|
|[![launch stack](/images/launch_stack_button.png)][us-east-1-hsm-signer] | US East (N. Virginia)|
|[![launch stack](/images/launch_stack_button.png)][us-east-2-hsm-signer] | US East (Ohio)|
|[![launch stack](/images/launch_stack_button.png)][us-west-2-hsm-signer] | US West (Oregon)|
|[![launch stack](/images/launch_stack_button.png)][sa-east-1-hsm-signer] | SA East (São Paulo)|

 
[Troubleshoot](#Troubleshooting-HSM-Installation)
 
**Details:** Just watch the show: You only need to change parameters in case the networks in the script overlap your networks. After the script finishes executing, a Step Function that starts automatically creates and configures your CloudHSM. After the Step Function finishes you can run step #2: Build Container.

This script creates a Step Function with name starting with **LaunchCloudHSMCluster**. In the AWS Console, go to Step Functions and find that function. Wait for the execution to finish and move to the next step.

 
### Build Container
  
|Deploy | Region |
|:---:|:---:|
|[![launch stack](/images/launch_stack_button.png)][us-east-1-container-signer] | US East (N. Virginia)|
|[![launch stack](/images/launch_stack_button.png)][us-east-2-container-signer] | US East (Ohio)|
|[![launch stack](/images/launch_stack_button.png)][us-west-2-container-signer] | US West (Oregon)|
|[![launch stack](/images/launch_stack_button.png)][sa-east-1-container-signer] | SA East (São Paulo)|


[Troubleshoot](#Troubleshooting-Container-Build)

**Details:** There are 3 parameters you need to define: CloudHSM Cluster Id, CloudHSM user and CloudHSM password. You can choose any user and password. (HSM Crypto Officer - the "root" user - password will be the same of your user password). To find out the CloudHSM Cluster Id is simple. In the AWS console type CloudHSM and you will find your cluster. In the CloudHSM clusters list you will see the Cluster Id in the format *"cluster-xxxxxxxxx"*.

 ![cloudhsm ip](/images/hsm-parameters.png)

After you run the CloudFormation script you should use the AWS Console and look for the service AWS CodeBuild. Find the build project named **xmlsigner** and start the build (image below). It will take around 10 minutes to finish. Now you can deploy the container in the ECS Fargate service.

 ![codebuild start](/images/start-build.png)


  ### Deploy Container on Fargate

|Deploy | Region |
|:---:|:---:|
|[![launch stack](/images/launch_stack_button.png)][us-east-1-ecs-signer] | US East (N. Virginia)|
|[![launch stack](/images/launch_stack_button.png)][us-east-2-ecs-signer] | US East (Ohio)|
|[![launch stack](/images/launch_stack_button.png)][us-west-2-ecs-signer] | US West (Oregon)|
|[![launch stack](/images/launch_stack_button.png)][sa-east-1-ecs-signer] | SA East (São Paulo)|

Just follow the steps and wait for the script to finish executing. It is ready for use!

[Troubleshoot](#Troubleshooting-ECS-Deploy)

## Using the container

The container executes the following operations:

 * List Keys
 * Create Key with a new certificate
 * Sign
 * Validate
 
 **IMPORTANT:** The recommended approach to delete keys from CloudHSM is to use the [CloudHSM client](https://docs.aws.amazon.com/cloudhsm/latest/userguide/install-and-configure-client-linux.html) software installed in a linux or windows virtual machine created in the same network as your CloudHSM. Certificate may be obtained from the Parameter Store and password from the Secrets Manager. This method is not implemented in the microservice because, at this moment, the CloudHSM keystore does not implement the deletion of keys. According to [this](https://docs.aws.amazon.com/cloudhsm/latest/userguide/alternative-keystore.html) documentation, deleting keys is not supported by CloudHSM Keystore. Use the CloudHSM's **key_mgmt_util** tool.
 
 After the script finishes executing, still inside the CloudFormation service, you should find in the details of the stack, a tab named **output**. That tab contains all the URLs of your microservice. You can create and list keys, sign and validate a document. 
 
 ![proposed solution](images/cf-outputs.png)

Signing process is executed using a self signed certificate. Everything is automatic. When you create a key a self signed certificate is already created for your convenience. 
 
Inside the folder **run** of this project there is a file name **certdata.json** and you can use it to define properties of your self signed certificate when creating keys. Please find below the commands you should use to operate the **xmlsigner** container: 
 
### Store the service URL in an environment variable:
(remove the / at the end of the url)
```
URL=<alb-url>
```

### List existing keys:
 
```
curl $URL/xml/listKeys
```

### Create keys:

```
curl --data "@run/certdata.json" $URL/xml/create/<my-key-label> -X POST -H "Content-Type: text/plain"
```

### Sign XML Document (using certificate w/ public key)

```
curl --data "@run/sample.xml" $URL/xml/sign/<my-key-label> -X POST -H "Content-Type: application/xml" >> run/signed.xml
```

### Validate Signed Document

```
curl --data "@run/signed.xml" $URL/xml/validate -X POST -H "Content-Type: application/xml"
```


[us-east-1-hsm-signer]: https://console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks/new?stackName=SignerHSM&templateURL=https://s3.amazonaws.com/signer-hsm/SignerHSM.yaml
[us-east-2-hsm-signer]: https://console.aws.amazon.com/cloudformation/home?region=us-east-2#/stacks/new?stackName=SignerHSM&templateURL=https://s3.amazonaws.com/signer-hsm/SignerHSM.yaml
[us-west-2-hsm-signer]: https://console.aws.amazon.com/cloudformation/home?region=us-west-2#/stacks/new?stackName=SignerHSM&templateURL=https://s3.amazonaws.com/signer-hsm/SignerHSM.yaml
[sa-east-1-hsm-signer]: https://console.aws.amazon.com/cloudformation/home?region=sa-east-1#/stacks/new?stackName=SignerHSM&templateURL=https://s3.amazonaws.com/signer-hsm/SignerHSM.yaml
[us-east-1-container-signer]: https://console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks/new?stackName=SignerContainer&templateURL=https://s3.amazonaws.com/signer-hsm/SignerContainer.yaml
[us-east-2-container-signer]: https://console.aws.amazon.com/cloudformation/home?region=us-east-2#/stacks/new?stackName=SignerContainer&templateURL=https://s3.amazonaws.com/signer-hsm/SignerContainer.yaml
[us-west-2-container-signer]: https://console.aws.amazon.com/cloudformation/home?region=us-west-2#/stacks/new?stackName=SignerContainer&templateURL=https://s3.amazonaws.com/signer-hsm/SignerContainer.yaml
[sa-east-1-container-signer]: https://console.aws.amazon.com/cloudformation/home?region=sa-east-1#/stacks/new?stackName=SignerContainer&templateURL=https://s3.amazonaws.com/signer-hsm/SignerContainer.yaml
[us-east-1-ecs-signer]: https://console.aws.amazon.com/cloudformation/home?region=us-east-1#/stacks/new?stackName=SignerECS&templateURL=https://s3.amazonaws.com/signer-hsm/SignerECS.yaml
[us-east-2-ecs-signer]: https://console.aws.amazon.com/cloudformation/home?region=us-east-2#/stacks/new?stackName=SignerECS&templateURL=https://s3.amazonaws.com/signer-hsm/SignerECS.yaml
[us-west-2-ecs-signer]: https://console.aws.amazon.com/cloudformation/home?region=us-west-2#/stacks/new?stackName=SignerECS&templateURL=https://s3.amazonaws.com/signer-hsm/SignerECS.yaml
[sa-east-1-ecs-signer]: https://console.aws.amazon.com/cloudformation/home?region=sa-east-1#/stacks/new?stackName=SignerECS&templateURL=https://s3.amazonaws.com/signer-hsm/SignerECS.yaml

## Troubleshoot

### Troubleshooting HSM Installation

 One of the configurations available in this step is choosing an AZ for your CloudHSM. In some cases, it is possible the service endpoint for CloudHSM is not available in the AZ chosen for your private subnet. Please, delete and recreate the Stack using a different AZ for your private subnet. To do that, delete the old stack and try again clicking in the stack button from [here](#Deploy-Network-and-CloudHSM). Once in the configuration menu, pick another AZ for your private subnet using the AZ select button. Some regions where this might happen are us-east-1, us-east-2, sa-east-1. For more information please click here.
 
### Troubleshooting Container Build
 
  In this step, CodeBuild tries to connect to GitHub to download the sources of the container. In case you never had configured GitHub credentials in CodeBuild you will get the error from the image below thrown by the CloudFomration. This means CodeBuild doesn't know how to connect to GitHub and you will have to configure credentials for this. In this case, relax, it is simple!
 
  ![git login failed](/images/create-build-failed.png)
 
 **Setup GitHub credentials in CodeBuild:** Go to the CodeBuild service in the AWS console. Once in the CodeBuild service menu, pretend you are creating a new build. Choose Create Build Project. In the configuration screen there is a section named Source. Choose `GitHub`, and `Connect using OAuth`. Once you connect press CANCEL and run the script again (validate you get the message **you are connected to Github using OAuth**). CodeBuild now knows how to fetch code from Github.
 
 **Delete the stack Signer container in CloudFormation service and recreate the Stack clicking [here](#Build-Container) to run the stack**
 
 ![setup git credentials](/images/configure-git.png)
 
 ### Troubleshooting ECS Deploy

 In case you never used the ECS Service you might be missing a service linked role. In case the error you got from the SignerECS Stack is the one from the image below, please refer to [this](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/using-service-linked-roles.html) documentation for details.
 
  ![linked role missing](images/service-linked-role.png)
 
 To fix the issue run the command below using the aws cli, delete the SignerECS stack and click again in the button above to redeploy the SignerECS stack.
 ```
 $ aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com
 ```
 
 ## Cleanup
 
  Cleanup procedure requires the execution of four manual steps. This is because a Step Function creates some of the assets in the first step (SignerHSM stack). To cleanup the environment execute the steps in the following order:
  
  1. Delete the third stack: `SignerECS`
  2. Manually delete the images in the ECR Repository named `xmlsigner`
  3. Delete the second stack: `SignerContainer`
  4. Manually delete the CloudHSM instance of your CloudHSM Cluster. Wait for it to finish (menu CloudHSM in the AWS console)
  5. Manually delete the CloudHSM Cluster
  6. Manually delete all **inbound** and all **outbound** rules from the Security Group that has a **group name** with the following pattern: `cloudhsm-<ClusterId>`, where ClusterId is the cluster id of your recently erased CloudHSM cluster in the format `cluster-xxxxxxxxx`
  7. Delete the third stack:  `SignerHSM`
