package com.microsoft;


import com.microsoft.azure.keyvault.KeyVaultClient;
import com.microsoft.azure.keyvault.models.KeyBundle;
import com.microsoft.azure.keyvault.models.KeyItem;
import com.microsoft.azure.keyvault.models.SecretBundle;
import com.microsoft.azure.keyvault.models.SecretItem;
import com.microsoft.azure.keyvault.requests.CreateKeyRequest;
import com.microsoft.azure.keyvault.requests.SetSecretRequest;
import com.microsoft.azure.keyvault.webkey.JsonWebKeySignatureAlgorithm;
import com.microsoft.azure.keyvault.webkey.JsonWebKeyType;
import com.microsoft.rest.ServiceCallback;
import com.microsoft.rest.ServiceFuture;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.codec.Utf8StringCodec;
import io.lettuce.core.masterslave.MasterSlave;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.Assert;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Azure key Vault example,
 * load authentication at runtime,
 * create a key vault and keys and secrets in vault.
 * Continuously poll key vault for keys.
 *
 * Note: It is also possible to do authenication using:
 * ClientCredential credentials = new ClientCredential(clientId, clientSecret);
 * AuthenticationResult result = context.acquireToken(resource, credentials, null).get();
 *
 * However in this sample we need to use the asymmetric authentication method because
 * we want to do certificate based authentication
 *
 * Also please note that OCT and EC types for keys are not supported yet
 *
 * In addition, the the field pfxPassword can be empty string if that was the way you created the certificate
 */

public class runAzureKeyVaultPoller
{
    @Autowired
    public static SignVerifySamplesKeyVault azureSignVerifier;

    private static final Logger logger = Logger.getLogger(runAzureKeyVaultPoller.class.getName());

    private static final String REDIS_PRIMARY_PWD = "https://kaisaykeyvault.vault.azure.net/secrets/redis-password-primary";

    private static final String REDIS_SECONDARY_PWD = "https://kaisaykeyvault.vault.azure.net/secrets/redis-password-secondary";

    private static final String REDIS2_PRIMARY_PWD = "https://kaisaykeyvault.vault.azure.net/secrets/redis2-password-primary";

    private static final String REDIS2_SECONDARY_PWD = "https://kaisaykeyvault.vault.azure.net/secrets/redis2-password-secondary";

    public static void main( String[] args )
    {
        try{
            Properties props = new Properties();
            props.load(Thread.currentThread().getContextClassLoader().getResourceAsStream("azure.properties"));

            final String clientId = props.getProperty("clientId");
            final String pfxPassword = props.getProperty("pfxPassword");
            final String path = props.getProperty("pathPfx");
            final String vaultUrl = props.getProperty("vaultBaseUrl");

            JavaKeyVaultAuthenticator authenticator = new JavaKeyVaultAuthenticator();

            KeyVaultClient kvClient = authenticator.getAuthentication(path, pfxPassword, clientId);

//            runSample(kvClient, vaultUrl);

            runRedisConnect(kvClient,vaultUrl);

//            runRedisClusterConnect(kvClient,vaultUrl);
        }
        catch(Exception e){
            e.printStackTrace();
        }
}

    private static List<RedisURI> preConnect(KeyVaultClient kvClient, String redisUri, int redishost) {
        String primaryPwd = "";
        String secondaryPwd = "";
        switch (redishost) {
            case 1 :
                primaryPwd = kvClient.getSecret(REDIS_PRIMARY_PWD).value();
                secondaryPwd = kvClient.getSecret(REDIS_SECONDARY_PWD).value();
                break;
            case 2 :
                primaryPwd = kvClient.getSecret(REDIS2_PRIMARY_PWD).value();
                secondaryPwd = kvClient.getSecret(REDIS2_SECONDARY_PWD).value();
                break;

        }

        List<RedisURI> nodes = Arrays.asList(
                RedisURI.Builder.redis(redisUri)
                        .withPassword(primaryPwd)
                        .withSsl(true)
                        .withPort(6380)
                        .build(),
                RedisURI.Builder.redis(redisUri)
                        .withPassword(secondaryPwd)
                        .withSsl(true)
                        .withPort(6380)
                        .build()
        );
        return nodes;
    }

    private static void runRedisClusterConnect(KeyVaultClient kvClient, String vaultUrl) {
        /**
         *         List<RedisURI> nodes = Arrays.asList(
         *                 RedisURI.Builder.redis(redisUri)
         *                         .withPassword(primaryPwd)
         *                         .withSsl(true)
         *                         .withPort(6380)
         *                         .build(),
         *                 RedisURI.Builder.redis(redisUri)
         *                         .withPassword(secondaryPwd)
         *                         .withSsl(true)
         *                         .withPort(6380)
         *          RedisClusterClient clusterClient = RedisClusterClient.create(nodes)
         */
        RedisClusterClient clusterClient = RedisClusterClient.create(preConnect(kvClient,"kvjavaapp2.redis.cache.windows.net",2));
        StatefulRedisClusterConnection<String, String> connection = clusterClient.connect();
        RedisAdvancedClusterCommands<String, String> syncCommands = connection.sync();
        syncCommands.set("key", "Hello, Redis!");
        Assert.isTrue("Hello, Redis!".equals(syncCommands.get("key")));
        logger.info("finish putting a key into redis cluster, closing");
        connection.close();
        clusterClient.shutdown();
    }

    private static void runRedisConnect(KeyVaultClient kvClient, String vaultUrl) {

        RedisClient redisClient = RedisClient.create();

        StatefulRedisConnection<String, String> connection = MasterSlave.connect(redisClient,new Utf8StringCodec(),
                preConnect(kvClient,"kvjavaapp1.redis.cache.windows.net",1));

        RedisCommands<String, String> syncCommands = connection.sync();

        syncCommands.set("key", "Hello, Redis!");

        connection.close();
        redisClient.shutdown();
        kvClient.getSecretAsync("https://kaisaykeyvault.vault.azure.net/secrets/redis-password-primary",
                new ServiceCallback<SecretBundle>() {
            @Override
            public void failure(Throwable t) {
                logger.severe("get password fail");
            }

            @Override
            public void success(SecretBundle result) {
                logger.info("try to create connection to Redis");
            }
        });
    }

    /**
     * Run the polling of Key Vault, and sign and verify operations
     * Async operations are also demonstrated
     * @param kvClient instance of Azure KeyVaultClient
     * @param vaultBaseUrl the url of the vault where the secret and keys are stored
     */
    public static void runSample(KeyVaultClient kvClient, String vaultBaseUrl){

        try {

            //Example of how to create a key using KV client, in the specified vault
            KeyBundle keyRSA = kvClient.createKey(new CreateKeyRequest.Builder(vaultBaseUrl,"keyRSA", JsonWebKeyType.RSA).build());
            System.out.println("The key Id is: " + keyRSA.key().kid());

            //Example: Async example of creating secret in specified vault, null passed for callback
            ServiceFuture<SecretBundle> secretAsync = kvClient.setSecretAsync(new SetSecretRequest.Builder(vaultBaseUrl,"secretNameInVault","secretValue").build(),null);
            System.out.println("The secret value is: " + secretAsync.get().value());

            System.out.println("Now listing keys and secrets in Azure Key Vault.");

            for(KeyItem ki: kvClient.listKeys(vaultBaseUrl,10)){ //list keys in vault up to a maximum of 10 records
                System.out.println("key tag is: " + ki.tags());
                System.out.println("key attributes: " + ki.attributes());
                String key_url = ki.kid();
                System.out.println("key id: " + key_url);

                runSigning(kvClient,key_url);

                KeyBundle keyBundle = kvClient.getKey(key_url);
                String kid = keyBundle.key().kid(); // get the key identifier
                System.out.println("Key in Key Vault: " + kid);
            }

            List<SecretItem> secretItems = kvClient.listSecrets(vaultBaseUrl,10);

            secretItems.stream().forEach(si -> {
                System.out.println("secret attributes: " + si.attributes());
                String secret_url = si.id();
                System.out.println("secret value: " + secret_url);

                SecretBundle secretBundle = kvClient.getSecret(secret_url);
                String secretValue = secretBundle.value();
                System.out.println("Secret in Key Vault Value: " + secretValue);
            });

            //asynchronous call to key vault with null passed for callback to list secrets in vault up to a maximum of 10 records
            ServiceFuture<List<SecretItem>> futSecrets = kvClient.listSecretsAsync(vaultBaseUrl,10,null);

            List<SecretItem> listSecrets = futSecrets.get();

            for(SecretItem sf: listSecrets){
                System.out.println("secret attributes: " + sf.attributes());
                String secret_url = sf.id();
                System.out.println("secret value: " + secret_url);

                ServiceFuture<SecretBundle> secretBundle = kvClient.getSecretAsync(secret_url, null);
                String secretValue = secretBundle.get().value();
                System.out.println("Secret in Key Vault Value: " + secretValue);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Run the sign and verify operations.
     * This would be useful in a case where you want to create signature for
     * some digital data and then verify that this signature is authentic.
     * @param kvClient instance of Azure KeyVaultClient
     * @param key_url the url of the key also known as Key Identifier
     */
    public static void runSigning( KeyVaultClient kvClient, String key_url) throws InterruptedException, ExecutionException, NoSuchAlgorithmException,
            SignatureException, NoSuchProviderException, InvalidKeyException {

        digestSignResult resultSign = azureSignVerifier.KeyVaultSign(kvClient,key_url,"SHA-256", JsonWebKeySignatureAlgorithm.RSNULL);

        Future<Boolean> verified256 = azureSignVerifier.KeyVaultVerify(kvClient,key_url,resultSign.getDigestInfo(),resultSign.getResultSign());
        System.out.println("Verified SHA 256: " + verified256.get().toString());

        Future<Boolean> verifiedREST256 = azureSignVerifier.KeyVaultVerifyREST(kvClient,key_url,JsonWebKeySignatureAlgorithm.RS256,resultSign.getResultSign(),resultSign.getDigestInfo());
        System.out.println("Verified SHA 256 with REST verify: " + verifiedREST256.get().toString());
    }

}

