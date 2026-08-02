package com.geek.codeguard.project.git;

import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.config.CodeGuardProperties;
import com.geek.codeguard.project.model.Project;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
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

    /**
     * 克隆仓库到工作区（存在则 pull 更新）。
     *
     * @param source  GITHUB / GITLAB（决定 Token 认证方式）
     * @param branch  分支；为空时使用远端默认分支
     */
    public Path syncRepo(String projectId, String source, String repoUrl, String branch, String token) {
        String safeBranch = (branch == null || branch.isBlank()) ? null : branch.trim();
        Path dir = props.resolvePaths().workspace.resolve(projectId);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodeEnum.CLONE_FAILED, "创建工作目录失败: " + e.getMessage());
        }
        boolean existingRepo = Files.isDirectory(dir.resolve(".git"));
        try {
            if (!existingRepo) {
                clone(source, repoUrl, safeBranch, token, dir);
            } else {
                pull(source, repoUrl, safeBranch, token, dir);
            }
            return dir;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("同步仓库失败 {}: {}", repoUrl, e.getMessage());
            throw new BusinessException(ErrorCodeEnum.CLONE_FAILED, friendlyError(source, repoUrl, token, e));
        }
    }

    /**
     * 把底层 git 异常翻译成可操作的提示：
     * - 需要认证但未配置 Token → 引导填写访问令牌（GitLab 私有/需认证实例最常见）
     * - 已配置 Token 但认证失败 → 提示检查 Token 与权限
     */
    private String friendlyError(String source, String repoUrl, String token, Exception e) {
        String msg = rootMessage(e);
        boolean authRequired = msg != null
                && msg.contains("Authentication is required");
        boolean authFailed = msg != null
                && (msg.contains("not authorized") || msg.contains("HTTP 401") || msg.contains("401 Unauthorized"));
        String label = Project.SOURCE_GITLAB.equalsIgnoreCase(source) ? "GitLab" : "GitHub";
        if (authRequired) {
            if (token == null || token.isBlank()) {
                return label + " 仓库需要认证才能拉取：请在该项目「访问令牌」中填写 "
                        + (Project.SOURCE_GITLAB.equalsIgnoreCase(source)
                        ? "GitLab Personal Access Token"
                        : "GitHub Token / PAT")
                        + " 后重试";
            }
            return label + " 认证失败：请检查访问令牌（Token）是否正确、是否已过期或缺少仓库权限";
        }
        if (authFailed) {
            return label + " 认证失败：请检查访问令牌（Token）是否正确、是否已过期或缺少仓库权限（"
                    + repoUrl + "）";
        }
        return "代码拉取失败: " + msg;
    }

    private void clone(String source, String url, String branch, String token, Path dir) throws Exception {
        var cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir.toFile())
                .setDepth(1)
                .setCloneSubmodules(false)
                .setCredentialsProvider(credentials(source, token));
        // 未指定分支时让 JGit 克隆远端默认分支（兼容默认分支为 master 的仓库）
        if (branch != null) {
            cmd.setBranch(branch);
        }
        Git git = cmd.call();
        git.close();
        log.info("克隆完成: {} -> {}", url, dir);
    }

    private void pull(String source, String url, String branch, String token, Path dir) throws Exception {
        try (Git git = Git.open(dir.toFile())) {
            if (branch != null) {
                git.pull()
                        .setRemote("origin")
                        .setRemoteBranchName(branch)
                        .setCredentialsProvider(credentials(source, token))
                        .call();
            } else {
                git.pull()
                        .setRemote("origin")
                        .setCredentialsProvider(credentials(source, token))
                        .call();
            }
            log.info("拉取更新完成: {} ({})", url, branch);
        } catch (GitAPIException e) {
            // pull 失败不致命（例如本地有改动），退回 fetch+reset
            log.warn("pull 失败，尝试 fetch+reset: {}", e.getMessage());
            try (Git git = Git.open(dir.toFile())) {
                git.fetch().setRemote("origin").setCredentialsProvider(credentials(source, token)).call();
                String resetRef = branch != null
                        ? "origin/" + branch
                        : defaultRemoteBranch(git);
                git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                        .setRef(resetRef).call();
            }
        }
    }

    /** 解析远端默认分支（origin/HEAD 指向的实际分支），取不到时回退 origin/HEAD */
    private String defaultRemoteBranch(Git git) {
        try {
            Ref head = git.getRepository().exactRef("refs/remotes/origin/HEAD");
            if (head != null && head.isSymbolic() && head.getTarget() != null) {
                return head.getTarget().getName();
            }
        } catch (Exception e) {
            log.warn("解析 origin/HEAD 失败: {}", e.getMessage());
        }
        return "refs/remotes/origin/HEAD";
    }

    private UsernamePasswordCredentialsProvider credentials(String source, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        // GitHub 使用 x-access-token 用户名；GitLab 使用 oauth2
        String user = Project.SOURCE_GITLAB.equalsIgnoreCase(source) ? "oauth2" : "x-access-token";
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
