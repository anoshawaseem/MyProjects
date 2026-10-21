package com.micomm.tenantmgmt.provider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Control-database access for club_module_providers (per-module provider overrides). */
@Repository
public class ClubModuleProviderRepository {

    /** A module's own provider account for one channel. */
    public record ModuleProvider(String moduleCode, String channel, UUID providerAccountId) {
    }

    private final JdbcTemplate jdbcTemplate;

    public ClubModuleProviderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ModuleProvider> findByClubProduct(UUID clubId, UUID productId) {
        return jdbcTemplate.query("""
                SELECT m.code, p.channel, p.provider_account_id
                FROM club_module_providers p
                JOIN product_modules m ON m.id = p.module_id
                WHERE p.club_id = ? AND p.product_id = ?
                ORDER BY m.sort_order, m.code, p.channel
                """, (rs, n) -> new ModuleProvider(rs.getString("code"), rs.getString("channel"),
                rs.getObject("provider_account_id", UUID.class)), clubId, productId);
    }

    public Optional<UUID> findAccountId(UUID clubId, UUID productId, String moduleCode, String channel) {
        return jdbcTemplate.queryForList("""
                SELECT p.provider_account_id
                FROM club_module_providers p
                JOIN product_modules m ON m.id = p.module_id AND m.code = ?
                WHERE p.club_id = ? AND p.product_id = ? AND p.channel = ?
                """, UUID.class, moduleCode, clubId, productId, channel).stream().findFirst();
    }

    public void upsert(UUID clubId, UUID productId, UUID moduleId, String channel, UUID providerAccountId) {
        jdbcTemplate.update("""
                INSERT INTO club_module_providers (club_id, product_id, module_id, channel, provider_account_id)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (club_id, product_id, module_id, channel)
                DO UPDATE SET provider_account_id = EXCLUDED.provider_account_id, updated_at = now()
                """, clubId, productId, moduleId, channel, providerAccountId);
    }

    public boolean delete(UUID clubId, UUID productId, UUID moduleId, String channel) {
        return jdbcTemplate.update("DELETE FROM club_module_providers"
                + " WHERE club_id = ? AND product_id = ? AND module_id = ? AND channel = ?",
                clubId, productId, moduleId, channel) > 0;
    }
}
