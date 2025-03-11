package io.f1r3fly;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.List;

@SpringBootApplication
public class Main implements CommandLineRunner {

    @Value("${discord.bot.token}")
    private String token;

    @Autowired
    private Bot bot;

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        startBot();
    }

    public void startBot() throws Exception {
        if (token == null || token.isEmpty()) {
            System.err.println("Token is missing. Please set the DISCORD_BOT_TOKEN environment variable.");
            System.exit(1);
        }

        JDABuilder.createDefault(token)
                .enableIntents(List.of(GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT))
                .setStatus(OnlineStatus.ONLINE)
                .setActivity(Activity.listening("your commands"))
                .addEventListeners(bot)
                .build();
    }
}
