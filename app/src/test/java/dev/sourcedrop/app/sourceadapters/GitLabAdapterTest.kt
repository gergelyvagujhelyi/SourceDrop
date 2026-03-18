package dev.sourcedrop.app.sourceadapters

import org.junit.Assert.assertEquals
import org.junit.Test

class GitLabAdapterTest {

    @Test
    fun parseGitLabUrl_simpleProject() {
        val (host, path) = GitLabAdapter.parseGitLabUrl("https://gitlab.com/owner/repo")
        assertEquals("gitlab.com", host)
        assertEquals("owner/repo", path)
    }

    @Test
    fun parseGitLabUrl_nestedGroup() {
        val (host, path) = GitLabAdapter.parseGitLabUrl("https://gitlab.com/group/subgroup/repo")
        assertEquals("gitlab.com", host)
        assertEquals("group/subgroup/repo", path)
    }

    @Test
    fun parseGitLabUrl_trailingSlash() {
        val (host, path) = GitLabAdapter.parseGitLabUrl("https://gitlab.com/owner/repo/")
        assertEquals("gitlab.com", host)
        assertEquals("owner/repo", path)
    }

    @Test
    fun parseGitLabUrl_withReleasesPath() {
        val (host, path) = GitLabAdapter.parseGitLabUrl("https://gitlab.com/owner/repo/-/releases")
        assertEquals("gitlab.com", host)
        assertEquals("owner/repo", path)
    }

    @Test
    fun parseGitLabUrl_selfHosted() {
        val (host, path) = GitLabAdapter.parseGitLabUrl("https://git.example.com/team/project")
        assertEquals("git.example.com", host)
        assertEquals("team/project", path)
    }

    @Test
    fun parseGitLabUrl_withGitSuffix() {
        val (host, path) = GitLabAdapter.parseGitLabUrl("https://gitlab.com/owner/repo.git")
        assertEquals("gitlab.com", host)
        assertEquals("owner/repo", path)
    }
}
