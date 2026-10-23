package com.meloong.pcl.community;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 免 Key 直查的输入清洗与名称反解（纯字符串逻辑，不联网）。 */
class CurseForgeKeylessTest {

    @Test
    void slugCandidatesCoverCommonForms() {
        List<String> candidates = CurseForgeKeyless.slugCandidates("Lan Server Properties");
        assertEquals("lan-server-properties", candidates.get(0));
        assertTrue(candidates.contains("lanserverproperties"));
        assertTrue(candidates.contains("lan_server_properties"));
    }

    @Test
    void slugCandidatesDropPunctuationAndKeepAscii() {
        List<String> candidates = CurseForgeKeyless.slugCandidates("Keep Some Inventory!");
        assertEquals("keep-some-inventory", candidates.get(0));
    }

    @Test
    void queryVariantsStripBracketVersionAndLoader() {
        List<String> variants = CurseForgeKeyless.queryVariants(
                "[自定义局域网联机] lanserverproperties-1.11.1-forge.jar");
        assertTrue(variants.contains("[自定义局域网联机] lanserverproperties-1.11.1-forge"));
        assertTrue(variants.contains("lanserverproperties-1.11.1-forge"));
        assertTrue(variants.contains("lanserverproperties"));
    }

    @Test
    void queryVariantsKeepPlainName() {
        List<String> variants = CurseForgeKeyless.queryVariants("jei");
        assertEquals(List.of("jei"), variants);
    }

    @Test
    void aliasesOfExtractBracketAbbreviationAndEnglishName() {
        List<String> aliases = CurseForgeKeyless.aliasesOf("[LSP] 自定义局域网联机 (Lan Server Properties)");
        assertTrue(aliases.contains("LSP"));
        assertTrue(aliases.contains("Lan Server Properties"));
    }

    @Test
    void aliasesOfFallsBackToLatinPart() {
        List<String> aliases = CurseForgeKeyless.aliasesOf("[TD] 暮色森林 Twilight Forest");
        assertTrue(aliases.contains("TD"));
        assertTrue(aliases.stream().anyMatch(item -> item.toLowerCase().contains("twilight")));
    }

    @Test
    void forgeCdnUrlSplitsFileId() {
        assertEquals("https://edge.forgecdn.net/files/4776/657/lanserverproperties-1.11.1-forge.jar",
                CurseForgeKeyless.forgeCdnUrl(4776657L, "lanserverproperties-1.11.1-forge.jar"));
    }
}
