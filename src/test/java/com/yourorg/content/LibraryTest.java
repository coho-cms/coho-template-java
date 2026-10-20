package com.yourorg.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cohocms.delivery.ClientOptions;
import com.cohocms.delivery.ContentConfig;
import com.cohocms.delivery.ReleasedConfig;
import com.cohocms.delivery.TransportResponse;
import com.yourorg.content.coho.Coho;
import com.yourorg.content.tools.Catalog;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Checks that hold for any project's types, so they keep passing after you regenerate for yours.
 * Add tests for your own reads beside them.
 */
class LibraryTest
{
    @Test
    void theLibraryNamesTheTypeModelItWasGeneratedFrom()
    {
        assertTrue(Coho.SOURCE.getSchemaHash().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void aClientAsksNothingUntilItIsReadFromAndTheHomePageIsOneRequest()
    {
        List<String> asked = new ArrayList<>();
        ClientOptions options = new ClientOptions().withTransport(request ->
        {
            asked.add(request.getUrl());
            byte[] body = "{\"roots\":[],\"entries\":{},\"unresolved\":{}}".getBytes(StandardCharsets.UTF_8);
            return CompletableFuture.completedFuture(new TransportResponse(200, Map.of("content-type", "application/json"), body));
        });
        Coho coho = new Coho(new ContentConfig(new ReleasedConfig("https://delivery.test", Coho.SOURCE.getProject()), "prod"), options);
        assertEquals(List.of(), asked);

        assertNull(CohoReads.homepage(coho));
        assertEquals(List.of("https://delivery.test/v1/projects/" + Coho.SOURCE.getProject() + "/prod/entries-by-slug/home-page?depth=2"), asked);
    }

    @Test
    void theCatalogCountsEveryTypeAHundredEntriesARequest()
    {
        // Sizes either side of a page, so some types take more than one request.
        List<String> types = Coho.CONTENT_TYPES.stream().sorted().toList();
        int[] pattern = {30, 100, 170, 0};
        Map<String, Integer> sizes = new HashMap<>();
        for (int i = 0; i < types.size(); i++)
        {
            sizes.put(types.get(i), pattern[i % 4]);
        }
        List<String> asked = new ArrayList<>();
        ClientOptions options = new ClientOptions().withTransport(request ->
        {
            Map<String, String> query = new HashMap<>();
            for (String part : URI.create(request.getUrl()).getQuery().split("&"))
            {
                query.put(part.substring(0, part.indexOf('=')), part.substring(part.indexOf('=') + 1));
            }
            String type = query.get("type");
            int offset = Integer.parseInt(query.get("offset"));
            int size = Math.max(0, Math.min(Integer.parseInt(query.get("limit")), sizes.get(type) - offset));
            asked.add(type + "@" + offset);
            StringBuilder roots = new StringBuilder();
            StringBuilder entries = new StringBuilder();
            for (int i = offset; i < offset + size; i++)
            {
                String id = "\"" + type + "-" + i + "\"";
                roots.append(i > offset ? "," : "").append(id);
                entries.append(i > offset ? "," : "").append(id).append(":{\"id\":").append(id).append(",\"slug\":").append(id)
                    .append(",\"type\":\"").append(type).append("\",\"fields\":{}}");
            }
            String body = "{\"roots\":[" + roots + "],\"entries\":{" + entries + "},\"unresolved\":{},\"hasMore\":" + (offset + size < sizes.get(type)) + "}";
            return CompletableFuture.completedFuture(new TransportResponse(200, Map.of("content-type", "application/json"), body.getBytes(StandardCharsets.UTF_8)));
        });

        List<Catalog.Counted> counted = Catalog.count(new ContentConfig(new ReleasedConfig("https://delivery.test", "p1"), "qa"), options);

        assertEquals(types.stream().map(type -> new Catalog.Counted(type, sizes.get(type))).toList(), counted);
        // One request per hundred entries, and one for an empty type: 170 asks at offsets 0 and 100.
        List<String> expected = new ArrayList<>();
        for (String type : types)
        {
            for (int page = 0; page < Math.max(1, (sizes.get(type) + 99) / 100); page++)
            {
                expected.add(type + "@" + page * 100);
            }
        }
        assertEquals(expected, asked);
    }
}
