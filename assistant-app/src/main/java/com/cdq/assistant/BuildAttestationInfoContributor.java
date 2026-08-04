package com.cdq.assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
final class BuildAttestationInfoContributor implements InfoContributor {

    private final String commit;
    private final boolean worktreeClean;

    BuildAttestationInfoContributor(
            @Value("${BUILD_COMMIT:unknown}") String commit,
            @Value("${BUILD_WORKTREE_CLEAN:false}") boolean worktreeClean
    ) {
        this.commit = commit;
        this.worktreeClean = worktreeClean;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("build", Map.of(
                "commit", commit,
                "worktreeClean", worktreeClean
        ));
    }
}
