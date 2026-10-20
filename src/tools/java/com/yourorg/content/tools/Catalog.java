package com.yourorg.content.tools;

import com.cohocms.delivery.ClientOptions;
import com.cohocms.delivery.CohoDeliveryException;
import com.cohocms.delivery.CohoEnvironment;
import com.cohocms.delivery.ContentConfig;
import com.cohocms.delivery.DeliveryClient;
import com.cohocms.delivery.EntryGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourorg.content.coho.Coho;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ./gradlew catalog}: every content type, and how many entries each has, read the way an
 * app reads them.
 *
 * <p>Released content by default, from delivery, with COHO_DELIVERY_URL and COHO_DELIVERY_KEY.
 * The ref is {@code --ref}, or COHO_REF, or the ref in coho.config.json.
 *
 * <p>{@code --preview} reads unreleased content instead, live through Coho, with the COHO_TOKEN
 * this library pulls its types with, or COHO_PREVIEW_TOKEN if that is set. The ref is
 * {@code --ref}, or {@code dev}.
 *
 * <p>The project, and for a preview Coho's URL and the account, come from coho.config.json unless
 * the environment or {@code .env} sets them, under the names an app's configuration uses.
 *
 * <p>A development tool: it is in a source set of its own, so it is never in the library.
 */
public final class Catalog
{
    /** How many entries of its own one content type has. */
    public record Counted(String type, int count)
    {
    }

    private Catalog()
    {
    }

    /**
     * Every content type this library was generated for, with how many entries of its own it has
     * on the config's ref. Each is counted by paging through its list, a hundred entries a
     * request; a type's list holds the types that extend it too, so only its own are counted. It
     * is for looking around, not for every page view.
     */
    public static List<Counted> count(ContentConfig config, ClientOptions options)
    {
        DeliveryClient delivery = new DeliveryClient(config.getDelivery(), options.withCacheSize(0));
        List<Counted> counted = new ArrayList<>();
        for (String type : Coho.CONTENT_TYPES.stream().sorted().toList())
        {
            int own = 0;
            int offset = 0;
            while (true)
            {
                EntryGraph page = delivery.entries(config.getRef(), type, null, 100, offset, null, null);
                for (String id : page.getRoots())
                {
                    if (type.equals(page.getEntries().get(id).getType()))
                    {
                        own++;
                    }
                }
                offset += page.getRoots().size();
                if (!page.getHasMore() || page.getRoots().isEmpty())
                {
                    break;
                }
            }
            counted.add(new Counted(type, own));
        }
        return counted;
    }

    public static void main(String[] args) throws IOException
    {
        List<String> arguments = Arrays.asList(args);
        boolean preview = arguments.contains("--preview");
        int refAt = arguments.indexOf("--ref");
        String askedRef = refAt >= 0 && refAt + 1 < args.length ? args[refAt + 1] : null;

        ObjectMapper json = new ObjectMapper();
        JsonNode configured = json.readTree(Path.of("coho.config.json").toFile());

        // The variables an app would set: the environment, then .env, with gaps filled from coho.config.json.
        Map<String, String> env = new HashMap<>(dotenv(Path.of(".env")));
        env.putAll(System.getenv());
        fill(env, "COHO_PROJECT", configured.path("project").asText(null));
        if (preview)
        {
            fill(env, "COHO_PREVIEW_TOKEN", env.get("COHO_TOKEN"));
            if (blank(env.get("COHO_PREVIEW_TOKEN")))
            {
                System.err.println("The catalog's --preview needs COHO_TOKEN in .env, a project token with read access. See .env.example.");
                System.exit(1);
            }
            fill(env, "COHO_URL", configured.path("url").asText(null));
            fill(env, "COHO_ACCOUNT", configured.path("account").asText(null));
            env.remove("COHO_DELIVERY_KEY");
            // Not the config's ref: that is a tag, and a preview reads branches.
            env.put("COHO_REF", askedRef != null ? askedRef : "dev");
        }
        else
        {
            env.remove("COHO_PREVIEW_TOKEN");
            fill(env, "COHO_REF", configured.path("ref").asText(null));
            if (askedRef != null)
            {
                env.put("COHO_REF", askedRef);
            }
        }

        ContentConfig config;
        try
        {
            config = CohoEnvironment.from(env::get);
        }
        catch (IllegalStateException error)
        {
            System.err.println("The catalog: " + error.getMessage() + ". See .env.example.");
            System.exit(1);
            return;
        }

        // Display names, from the type model the code was generated from.
        JsonNode model = json.readTree(Path.of(configured.path("typeModel").asText("coho-ir.json")).toFile());
        List<Counted> counted;
        try
        {
            counted = count(config, new ClientOptions());
        }
        catch (CohoDeliveryException error)
        {
            // Coho's own words, such as KEY_REQUIRED or PROJECT_NOT_FOUND, and what to check.
            System.err.println(error.getMessage());
            System.exit(1);
            return;
        }

        int typeWidth = counted.stream().mapToInt(each -> each.type().length()).max().orElse(0);
        int nameWidth = counted.stream().mapToInt(each -> model.path("types").path(each.type()).path("name").asText("").length()).max().orElse(0);
        int total = counted.stream().mapToInt(Counted::count).sum();
        System.out.println((preview ? "Unreleased content" : "Content") + " on " + config.getRef() + ":\n");
        for (Counted each : counted)
        {
            String name = model.path("types").path(each.type()).path("name").asText("");
            System.out.printf("  %-" + typeWidth + "s   %-" + nameWidth + "s   %5d%n", each.type(), name, each.count());
        }
        System.out.printf("%n  %d %s in %d %s%n", total, total == 1 ? "entry" : "entries", counted.size(), counted.size() == 1 ? "type" : "types");
    }

    private static Map<String, String> dotenv(Path file) throws IOException
    {
        Map<String, String> values = new HashMap<>();
        if (Files.exists(file))
        {
            for (String line : Files.readAllLines(file))
            {
                int equals = line.indexOf('=');
                if (equals > 0 && !line.stripLeading().startsWith("#"))
                {
                    values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim().replaceAll("^\"|\"$", ""));
                }
            }
        }
        return values;
    }

    private static void fill(Map<String, String> env, String name, String value)
    {
        if (blank(env.get(name)) && value != null)
        {
            env.put(name, value);
        }
    }

    private static boolean blank(String value)
    {
        return value == null || value.isBlank();
    }
}
