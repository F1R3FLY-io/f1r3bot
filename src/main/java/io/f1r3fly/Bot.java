package io.f1r3fly;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import io.f1r3fly.grcp.Deployer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class Bot extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(Bot.class);
    private final Deployer deployer;

    @Autowired
    public Bot(Deployer deployer) {
        this.deployer = deployer;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        String message = event.getMessage().getContentRaw();
        String commandPattern = "(?i)(!deploy|!eval|!help)(\\s+-f\\s+)?(\\S+)?\\s*(.*)";
        Pattern pattern = Pattern.compile(commandPattern);
        Matcher matcher = pattern.matcher(message);

        if (matcher.matches()) {
            String command = matcher.group(1).toLowerCase();
            String fileFlag = matcher.group(2);
            String fileName = matcher.group(3);
            String argument = matcher.group(4).trim();

            if (fileFlag != null && (fileName == null || fileName.isEmpty())) {
                event.getChannel().sendMessage("Please specify the file name after the -f flag.").queue();
                return;
            }

            if (fileFlag != null) {
                processCommandWithFile(event, command, fileName);
            } else {
                switch (command) {
                    case "!deploy":
                        if (argument.isEmpty()) {
                            event.getChannel().sendMessage("Please provide the Rholang code to deploy.").queue();
                        } else {
                            deployRholangCode(event, argument);
                        }
                        break;
                    case "!eval":
                        if (argument.isEmpty()) {
                            event.getChannel().sendMessage("Please provide the Rholang code to evaluate.").queue();
                        } else {
                            evalRholangCode(event, argument);
                        }
                        break;
                    case "!help":
                        showHelp(event);
                        break;
                    default:
                        showHelp(event);
                        break;
                }
            }
        } else if (message.startsWith("!")) {
            showHelp(event);
        }
    }

    private void processCommandWithFile(MessageReceivedEvent event, String command, String fileName) {
        if (event.getMessage().getAttachments().isEmpty()) {
            event.getChannel().sendMessage("Please attach the file to the message.").queue();
            return;
        }

        event.getMessage().getAttachments().stream()
                .filter(attachment -> attachment.getFileName().equals(fileName))
                .findFirst()
                .ifPresentOrElse(attachment -> {
                    attachment.downloadToFile(new java.io.File(fileName))
                            .thenAccept(file -> {
                                try {
                                    String fileContent = new String(Files.readAllBytes(file.toPath()));
                                    if ("!deploy".equals(command)) {
                                        deployRholangCodeFromFile(event, fileContent);
                                    } else if ("!eval".equals(command)) {
                                        evalRholangCodeFromFile(event, fileContent);
                                    }
                                } catch (IOException e) {
                                    event.getChannel().sendMessage("Failed to read the file: " + e.getMessage()).queue();
                                }
                            })
                            .exceptionally(throwable -> {
                                event.getChannel().sendMessage("Failed to download the file: " + throwable.getMessage()).queue();
                                return null;
                            });
                }, () -> event.getChannel().sendMessage("File not found in attachments.").queue());
    }

    private void deployRholangCode(MessageReceivedEvent event, String rholangCode) {
        try {
            LOGGER.info("Deploying code: " + rholangCode);
            String result = deployer.deploy(rholangCode.replaceAll("[\\n\\r\\t]", " "), false, Deployer.RHOLANG);
            event.getChannel().sendMessage("Deployed successfully. Block hash: " + result).queue();
        } catch (Exception e) {
            event.getChannel().sendMessage("Deployment failed: " + e.getMessage()).queue();
        }
    }

    private void evalRholangCode(MessageReceivedEvent event, String rholangCode) {
        LOGGER.info("Evaluating code");

        deployer.eval(rholangCode.replaceAll("[\\n\\r\\t]", " "))
                .subscribe().with(result -> {
                    LOGGER.info("Eval result: " + result);
                    event.getChannel().sendMessage("Eval result: " + result).queue();
                }, failure -> {
                    event.getChannel().sendMessage("Eval failed: " + failure.getMessage()).queue();
                });
    }

    private void deployRholangCodeFromFile(MessageReceivedEvent event, String fileContent) {
        try {
            LOGGER.info("Deploying code from file.");
            String result = deployer.deploy(fileContent, false, Deployer.RHOLANG);
            event.getChannel().sendMessage("Deployed successfully. Block hash: " + result).queue();
        } catch (Exception e) {
            event.getChannel().sendMessage("Deployment failed: " + e.getMessage()).queue();
        }
    }

    private void evalRholangCodeFromFile(MessageReceivedEvent event, String fileContent) {
        LOGGER.info("Evaluating code from file.");

        deployer.eval(fileContent)
                .subscribe().with(result -> {
                    LOGGER.info("Eval result: " + result);
                    event.getChannel().sendMessage("Eval result: " + result).queue();
                }, failure -> {
                    event.getChannel().sendMessage("Eval failed: " + failure.getMessage()).queue();
                });
    }

    private void showHelp(MessageReceivedEvent event) {
        event.getChannel().sendMessage("Commands: \n!deploy inline rholang-code; \n!deploy -f fileName;  " +
                "\n!eval inline rholang-code; \n!eval -f fileName;").queue();
    }

}

