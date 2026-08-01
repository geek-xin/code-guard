package com.geek.codeguard.project.git;

import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.config.CodeGuardProperties;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 基于 JGit 的代码拉取：支持 GitHub / GitLab 公开与私有仓库。
 */
@Service
@Slf4j
public class GitService {

    private final CodeGuardProperties props;

    public GitService(CodeGuardProperties props) {
        this.props = props;
    }

    /** 克隆仓库到工作区（存在则 pull 更新） */
    public Path syncRepo(String projectId, String repoUrl, String branch, String token) {
        String safeBranch = (branch == null || branch.isBlank()) ? "main" : branch;
        Path dir = props.resolvePaths().workspace.resolve(projectId);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodeEnum.CLONE_FAILED, "创建工作目录失败: " + e.getMessage());
        }
        boolean existingRepo = Files.isDirectory(dir.resolve(".git"));
        try {
            if (!existingRepo) {
                clone(repoUrl, safeBranch, token, dir);
            } else {
                pull(repoUrl, safeBranch, token, dir);
            }
            return dir;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("同步仓库失败 {}: {}", repoUrl, e.getMessage());
            throw new BusinessException(ErrorCodeEnum.CLONE_FAILED, "代码拉取失败: " + rootMessage(e));
        }
    }

    private void clone(String url, String branch, String token, Path dir) throws Exception {
        Git git = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir.toFile())
                .setBranch(branch)
                .setDepth(1)
                .setCloneSubmodules(false)
                .setCredentialsProvider(credentials(token))
                .call();
        git.close();
        log.info("克隆完成: {} -> {}", url, dir);
    }

    private void pull(String url, String branch, String token, Path dir) throws Exception {
        try (Git git = Git.open(dir.toFile())) {
            git.pull()
                    .setRemote("origin")
                    .setRemoteBranchName(branch)
                    .setCredentialsProvider(credentials(token))
                    .call();
            log.info("拉取更新完成: {} ({})", url, branch);
        } catch (GitAPIException e) {
            // pull 失败不致命（例如本地有改动），退回 fetch+reset
            log.warn("pull 失败，尝试 fetch+reset: {}", e.getMessage());
            try (Git git = Git.open(dir.toFile())) {
                git.fetch().setRemote("origin").setCredentialsProvider(credentials(token)).call();
                git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                        .setRef("origin/" + branch).call();
            }
        }
    }

    private UsernamePasswordCredentialsProvider credentials(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        // GitHub 使用 x-access-token 用户名；GitLab 使用 oauth2
        String user = "x-access-token";
        return new UsernamePasswordCredentialsProvider(user, token);
    }

    public void deleteWorkspace(String projectId) {
        Path dir = props.resolvePaths().workspace.resolve(projectId);
        deleteRecursively(dir);
    }

    public static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getMessage();
        }
        return msg == null ? e.getClass().getSimpleName() : msg.split("\n")[0];
    }
}
