package com.luxera.companion.application.manifest;

import com.luxera.companion.application.domain.ApplicationRecord;
import com.luxera.companion.application.domain.ApplicationStatus;
import com.luxera.companion.application.domain.ApplicationVersionRecord;
import com.luxera.companion.application.repository.ApplicationCapabilityRepository;
import com.luxera.companion.application.repository.ApplicationRepository;
import com.luxera.companion.application.repository.ApplicationVersionRepository;
import com.luxera.companion.application.repository.CapabilityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * manifest 落库: 版本行、能力行、关联行都要对得上, 且重复同步是幂等的。
 *
 * <p>为什么值得一轮子步骤: {@code action_invocation} 要指向一个版本行, 生命周期状态机要有
 * 可持久化的状态字段 —— 这两件事都建在这张表上。同步写歪了, 症状会出现在很远的地方
 * ("这次调用跑的是哪一版"答不出来)。
 */
@ActiveProfiles("test")
@SpringBootTest
class ManifestCatalogueSyncTest {

    private static final String MANIFEST_PATH =
            "applications/tictactoe/1.0.0/application-manifest.json";

    @Autowired
    ManifestParser parser;

    @Autowired
    ManifestValidator validator;

    @Autowired
    ManifestCatalogueSync sync;

    @Autowired
    ApplicationRepository applications;

    @Autowired
    ApplicationVersionRepository versions;

    @Autowired
    CapabilityRepository capabilities;

    @Autowired
    ApplicationCapabilityRepository applicationCapabilities;

    @Test
    void syncWritesVersionRowWithHashAndPublishedStatus() throws Exception {
        String json = readManifest();
        ApplicationManifest manifest = parser.parse(json);
        validator.validate(manifest);

        ApplicationVersionRecord row = sync.sync(manifest, json);

        ApplicationVersionRecord stored = versions.findById(row.getId()).orElseThrow();
        assertEquals("com.luxera.tictactoe", stored.getApplicationId());
        assertEquals("1.0.0", stored.getVersion());
        assertEquals(ManifestCatalogueSync.sha256(json), stored.getManifestHash(),
                "hash 必须是原始 JSON 的 sha256 —— 它是'发布后有没有被动过'的唯一判据");
        assertEquals("PUBLISHED", stored.getStatus());
        assertEquals("NATIVE", stored.getRuntimeType());
        assertNotNull(stored.getPublishedAt());
        assertEquals(json, stored.getManifestJson(), "原始 JSON 逐字节保留");
    }

    @Test
    void syncWritesApplicationAndCapabilityRows() throws Exception {
        String json = readManifest();
        ApplicationManifest manifest = parser.parse(json);
        validator.validate(manifest);
        ApplicationVersionRecord row = sync.sync(manifest, json);

        ApplicationRecord application = applications.findById("com.luxera.tictactoe").orElseThrow();
        assertEquals("井字棋", application.getName());
        assertEquals("game", application.getCategory());
        assertEquals(ApplicationStatus.PUBLISHED.name(), application.getStatus());
        assertEquals("1.0.0", application.getLatestVersion());

        assertTrue(capabilities.findById("game.play").isPresent(), "能力目录要有 game.play");
        assertEquals(1, applicationCapabilities.findByApplicationVersionId(row.getId()).size());
        assertEquals("game.play", applicationCapabilities
                .findByApplicationVersionId(row.getId()).get(0).getCapabilityId());
    }

    @Test
    void resyncIsIdempotentAndKeepsTheSameRow() throws Exception {
        String json = readManifest();
        ApplicationManifest manifest = parser.parse(json);
        validator.validate(manifest);

        ApplicationVersionRecord first = sync.sync(manifest, json);
        ApplicationVersionRecord second = sync.sync(manifest, json);

        assertEquals(first.getId(), second.getId(),
                "重复同步必须复用同一行 —— 每次启动插一条新版本行会让历史版本爆炸");
        assertEquals(1, applicationCapabilities.findByApplicationVersionId(first.getId()).size(),
                "能力关联不能重复插入");
    }

    private static String readManifest() throws Exception {
        return StreamUtils.copyToString(
                new ClassPathResource(MANIFEST_PATH).getInputStream(), StandardCharsets.UTF_8);
    }
}
