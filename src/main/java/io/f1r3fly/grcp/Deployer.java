package io.f1r3fly.grcp;

import casper.CasperMessage;
import casper.ProposeServiceCommon;
import casper.DeployServiceCommon;
import casper.v1.DeployServiceGrpc;
import casper.v1.ProposeServiceGrpc;
import com.google.protobuf.ByteString;
import com.google.protobuf.ProtocolStringList;
import com.rfksystems.blake2b.Blake2b;
import com.rfksystems.blake2b.security.Blake2bProvider;
import fr.acinq.secp256k1.Hex;
import fr.acinq.secp256k1.Secp256k1;
import io.f1r3fly.errors.Blake2Exception;
import io.f1r3fly.errors.F1r3flyDeployError;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import repl.ReplGrpc;
import repl.ReplOuterClass;
import servicemodelapi.ServiceErrorOuterClass;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


@Component
public class Deployer {
    public static final String RHOLANG = "rholang";
    public static final String METTA_LANGUAGE = "metta"; //for future -l flag


    @Value("${rholang.signing.key}")
    private String signingKeyHex;

    @Value("${grpc.node.host}")
    private String nodeHost;

    @Value("${grpc.node.port}")
    private int grpcPort;

    private static final Logger LOGGER = LoggerFactory.getLogger(Deployer.class);

    private static final Duration INIT_DELAY = Duration.ofSeconds(2);
    private static final Duration MAX_DELAY = Duration.ofSeconds(100);
    private static final int RETRIES = 10;
    private static final int MAX_MESSAGE_SIZE = Integer.MAX_VALUE; // ~2 GB

    private byte[] signingKey;
    private DeployServiceGrpc.DeployServiceFutureStub deployService;
    private ProposeServiceGrpc.ProposeServiceFutureStub proposeService;
    private ReplGrpc.ReplFutureStub replService;

    private String previousResult = "";
    private String currentResult = "";;


    @PostConstruct
    public void init() {
        Security.addProvider(new Blake2bProvider());

        this.signingKey = Hex.decode(signingKeyHex);
        ManagedChannel channel = ManagedChannelBuilder.forAddress(nodeHost, grpcPort).usePlaintext().build();

        this.deployService = DeployServiceGrpc.newFutureStub(channel)
                .withMaxInboundMessageSize(MAX_MESSAGE_SIZE)
                .withMaxOutboundMessageSize(MAX_MESSAGE_SIZE);
        this.proposeService = ProposeServiceGrpc.newFutureStub(channel)
                .withMaxInboundMessageSize(MAX_MESSAGE_SIZE)
                .withMaxOutboundMessageSize(MAX_MESSAGE_SIZE);
        this.replService = ReplGrpc.newFutureStub(channel)
                .withMaxInboundMessageSize(MAX_MESSAGE_SIZE)
                .withMaxOutboundMessageSize(MAX_MESSAGE_SIZE);
    }

    public String deploy(String rhoCode, boolean useBiggerRhloPrice, String language) throws F1r3flyDeployError {
        try {

            int maxRholangInLogs = 2000;
            LOGGER.debug("Rholang code {}", rhoCode.length() > maxRholangInLogs ? rhoCode.substring(0, maxRholangInLogs) : rhoCode);

            long phloLimit = useBiggerRhloPrice ? 5_000_000_000L : 50_000L;

            // Make deployment
            CasperMessage.DeployDataProto deployment = CasperMessage.DeployDataProto.newBuilder()
                    .setTerm(rhoCode)
                    .setTimestamp(0)
                    .setPhloPrice(1)
                    .setPhloLimit(phloLimit)
                    .setShardId("root")
                    //.setLanguage(language)
                    .build();

            // Sign deployment
            CasperMessage.DeployDataProto signed = signDeploy(deployment);


            // Deploy
            Uni<String> deployVolumeContract =
                    Uni.createFrom().future(deployService.doDeploy(signed))
                            .flatMap(deployResponse -> {
//                        LOGGER.trace("Deploy Response {}", deployResponse);
                                if (deployResponse.hasError()) {
                                    return this.<String>fail(rhoCode, deployResponse.getError());
                                } else {
                                    return succeed(deployResponse.getResult());
                                }
                            })
                            .flatMap(deployResult -> {
                                String deployId = deployResult.substring(deployResult.indexOf("DeployId is: ") + 13, deployResult.length());
                                return Uni.createFrom().future(proposeService.propose(ProposeServiceCommon.ProposeQuery.newBuilder().setIsAsync(false).build()))
                                        .flatMap(proposeResponse -> {
                                            LOGGER.info("Propose Response {}", proposeResponse);
                                            if (proposeResponse.hasError()) {
                                                LOGGER.info("Propose failed: {}", proposeResponse.getError());
                                                return this.<String>fail(rhoCode, proposeResponse.getError());
                                            } else {
                                                LOGGER.info("Propose succed: {}", proposeResponse.getError());
                                                return succeed(deployId);
                                            }
                                        });
                            })
                            .flatMap(deployId -> {
                                LOGGER.info("deployId: {}", deployId);
                                ByteString b64 = ByteString.copyFrom(Hex.decode(deployId));
                                LOGGER.info("b64: {}", b64);
                                return Uni.createFrom().future(deployService.findDeploy(DeployServiceCommon.FindDeployQuery.newBuilder().setDeployId(b64).build()))
                                        .flatMap(findResponse -> {
                                            LOGGER.debug("Find Response {}", findResponse);
                                            if (findResponse.hasError()) {
                                                return this.<String>fail(rhoCode, findResponse.getError());
                                            } else {
                                                return succeed(findResponse.getBlockInfo().getBlockHash());
                                            }
                                        });
                            })
                            .flatMap(blockHash -> {
                                LOGGER.debug("Block Hash {}", blockHash);
                                return Uni.createFrom().future(deployService.isFinalized(DeployServiceCommon.IsFinalizedQuery.newBuilder().setHash(blockHash).build()))
                                        .flatMap(isFinalizedResponse -> {
                                            LOGGER.debug("isFinalizedResponse {}", isFinalizedResponse);
                                            if (isFinalizedResponse.hasError() || !isFinalizedResponse.getIsFinalized()) {
                                                return fail(rhoCode, isFinalizedResponse.getError());
                                            } else {
                                                return succeed(blockHash);
                                            }
                                        })
                                        .onFailure().retry()
                                        .withBackOff(INIT_DELAY, MAX_DELAY)
                                        .atMost(RETRIES);
                            });

            // Drummer Hoff Fired It Off
            return deployVolumeContract.await().indefinitely();
        } catch (Exception e) {
            if (e instanceof F1r3flyDeployError) {
                throw (F1r3flyDeployError) e;
            } else {
                LOGGER.warn("failed to deploy Rho {}", rhoCode, e);
                throw new F1r3flyDeployError(rhoCode, "Failed to deploy", e);
            }
        }
    }

    private CasperMessage.DeployDataProto signDeploy(CasperMessage.DeployDataProto deploy) {
        final MessageDigest digest;

        try {
            digest = MessageDigest.getInstance(Blake2b.BLAKE2_B_256);
        } catch (NoSuchAlgorithmException e) {
            throw new Blake2Exception("Can't load MessageDigest instance (BLAKE2_B_256)", e);
        }

        final Secp256k1 secp256k1 = Secp256k1.get();

        CasperMessage.DeployDataProto.Builder builder = CasperMessage.DeployDataProto.newBuilder();

        builder
                .setTerm(deploy.getTerm())
                .setTimestamp(deploy.getTimestamp())
                .setPhloPrice(deploy.getPhloPrice())
                .setPhloLimit(deploy.getPhloLimit())
                .setValidAfterBlockNumber(deploy.getValidAfterBlockNumber())
                .setShardId(deploy.getShardId());

        CasperMessage.DeployDataProto signed = builder.build();

        byte[] serial = signed.toByteArray();
        digest.update(serial);
        byte[] hashed = digest.digest();
        byte[] signature = secp256k1.compact2der(secp256k1.sign(hashed, signingKey));
        byte[] pubKey = secp256k1.pubkeyCreate(signingKey);

        CasperMessage.DeployDataProto.Builder outbound = signed.toBuilder();
        outbound
                .setSigAlgorithm("secp256k1")
                .setSig(ByteString.copyFrom(signature))
                .setDeployer(ByteString.copyFrom(pubKey));

        return outbound.build();
    }

    private <T> Uni<T> fail(String rho, ServiceErrorOuterClass.ServiceError error) {
        return Uni.createFrom().failure(new F1r3flyDeployError(rho, gatherErrors(error)));
    }

    private String gatherErrors(ServiceErrorOuterClass.ServiceError error) {
        ProtocolStringList messages = error.getMessagesList();
        return messages.stream().collect(Collectors.joining("\n"));
    }

    private <T> Uni<T> succeed(T t) {
        return Uni.createFrom().item(t);
    }

    public String deployFromFile(String filePath, boolean useBiggerRhloPrice, String language) throws F1r3flyDeployError {
        try {
            String rhoCode = new String(Files.readAllBytes(Paths.get(filePath)));
            return deploy(rhoCode, useBiggerRhloPrice, language);
        } catch (IOException e) {
            throw new F1r3flyDeployError("", "Failed to read file: " + filePath, e);
        }
    }

    public Uni<String> eval(String rhoCode) {
        ReplOuterClass.EvalRequest request = ReplOuterClass.EvalRequest.newBuilder()
                .setProgram(rhoCode)
                .setPrintUnmatchedSendsOnly(true)
                .build();

        LOGGER.info("Sending eval request");

        return Uni.createFrom().completionStage(CompletableFuture.supplyAsync(() -> {
                    try {
                        return replService.eval(request).get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }))
                .flatMap(response -> {
                    String output = response.getOutput().trim();
                    LOGGER.info("Received response: " + output);
                    if (output.isEmpty()) {
                        return Uni.createFrom().failure(new RuntimeException("Eval returned empty output"));
                    } else {
                        previousResult = currentResult;
                        currentResult = output;
                        return Uni.createFrom().item(getDifference(previousResult, currentResult));
                    }
                })
                .onFailure().retry()
                .withBackOff(Duration.ofMillis(100), Duration.ofSeconds(5))
                .atMost(10)
                .onFailure().recoverWithItem(e -> {
                    LOGGER.error("Eval failed", e);
                    return "Eval failed: " + e.getMessage();
                });
    }

    /*
    This code that looks for the difference between two states and eval commands.
    That is, we have state n and also state n-1, actually I look for the difference and return it as a result.
    This is not a very good idea, because according to Greg, we need to rewrite the GrpcRepl server, but we don't have time for that at the moment.
    In the future, the GrpcRepl service should not return concatenated results, but specific responses to executed contracts.
     */
    private String getDifference(String previous, String current) {
        // Separate Storage Contents from previous and current results
        String previousStorageContents = previous.contains("Storage Contents:") ? previous.split("Storage Contents:")[1].trim() : "";
        String currentStorageContents = current.contains("Storage Contents:") ? current.split("Storage Contents:")[1].trim() : "";

        // Convert Storage Contents to sets and clean them
        Set<String> previousSet = cleanSet(new HashSet<>(Set.of(previousStorageContents.split("\\|"))));
        Set<String> currentSet = cleanSet(new HashSet<>(Set.of(currentStorageContents.split("\\|"))));

        // Remove previous set from current set to find the difference
        currentSet.removeAll(previousSet);

        // Combine Deployment cost and the difference in Storage Contents
        String result = current.split("Storage Contents:")[0].trim();
        if (!currentSet.isEmpty()) {
            result += "\nStorage Contents:\n" + String.join(" | ", currentSet);
        }

        LOGGER.info("Difference Set: {}", currentSet);
        return result;
    }

    private Set<String> cleanSet(Set<String> set) {
        return set.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }



//    /**
//     * @param rhoCode Rholang code to run, not eval
//     * @return Output of the run
//     */
//    public Uni<String> run(String rhoCode) {
//        ReplOuterClass.CmdRequest request = ReplOuterClass.CmdRequest.newBuilder()
//                .setLine(rhoCode)
//                .build();
//
//        return Uni.createFrom().completionStage(CompletableFuture.supplyAsync(() -> {
//                    try {
//                        return replService.run(request).get();
//                    } catch (Exception e) {
//                        throw new RuntimeException(e);
//                    }
//                }))
//                .flatMap(response -> {
//                    String output = response.getOutput().trim();
//                    if (output.isEmpty()) {
//                        return Uni.createFrom().failure(new F1r3flyDeployError(rhoCode, "Run returned empty output"));
//                    } else {
//                        return Uni.createFrom().item(output);
//                    }
//                })
//                .onFailure().retry()
//                .withBackOff(INIT_DELAY, MAX_DELAY)
//                .atMost(RETRIES)
//                .onFailure().recoverWithItem(e -> {
//                    LOGGER.error("Run failed", e);
//                    return "Run failed: " + e.getMessage();
//                });
//    }



}
