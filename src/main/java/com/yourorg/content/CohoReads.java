package com.yourorg.content;

import com.cohocms.delivery.EntryBase;
import com.cohocms.delivery.ReadOptions;
import com.yourorg.content.coho.Coho;
import org.jspecify.annotations.Nullable;

/**
 * Reads of your own, built on the generated ones.
 *
 * <p>The {@code coho} package is generated from the project's content types: after a schema
 * change, run {@code ./gradlew cohoPull cohoGenerate}, and never edit it by hand. Your reads
 * belong here, beside it. Regeneration never touches this file, and a schema change that removes
 * something these reads use fails this library's build rather than an app at runtime.
 */
public final class CohoReads
{
    private CohoReads()
    {
    }

    /**
     * An example of a read of your own: the home page, and everything it links to two levels
     * deep, in one request. Replace it with yours, or remove it.
     */
    public static @Nullable EntryBase homepage(Coho coho)
    {
        return coho.bySlug("home-page", ReadOptions.depth(2));
    }
}
