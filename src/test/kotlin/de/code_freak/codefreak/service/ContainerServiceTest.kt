package de.code_freak.codefreak.service

import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.DockerClient.ListContainersParam
import de.code_freak.codefreak.config.DockerConfiguration
import de.code_freak.codefreak.entity.Answer
import de.code_freak.codefreak.util.TarUtil
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner
import java.util.UUID

@RunWith(SpringJUnit4ClassRunner::class)
@ContextConfiguration(classes = [DockerConfiguration::class, ContainerService::class])
@ActiveProfiles("test")
internal class ContainerServiceTest {

  @Autowired
  lateinit var docker: DockerClient

  @Autowired
  lateinit var containerService: ContainerService

  val taskSubmission by lazy {
    val mock = mock(Answer::class.java)
    `when`(mock.id).thenReturn(UUID(0, 0))
    mock
  }

  @Before
  fun pullImages() {
    containerService.pullDockerImages()
  }

  @Before
  @After
  fun tearDown() {
    // delete all containers before and after each run
    listIdeContainer().parallelStream().forEach {
      docker.killContainer(it.id())
      docker.removeContainer(it.id())
    }
  }

  @Test
  fun `New IDE container is started`() {
    val containerId = containerService.startIdeContainer(taskSubmission)
    val containers = listIdeContainer()
    assertThat(containers, hasSize(1))
    assertThat(containers[0].id(), equalTo(containerId))
  }

  @Test
  fun `Existing IDE container is used`() {
    val containerId1 = containerService.startIdeContainer(taskSubmission)
    containerService.startIdeContainer(taskSubmission)
    val containers = listIdeContainer()
    assertThat(containers, hasSize(1))
    assertThat(containers[0].id(), equalTo(containerId1))
  }

  @Test
  fun `files are extracted to project directory`() {
    `when`(taskSubmission.files).thenReturn(TarUtil.createTarFromDirectory(ClassPathResource("tasks/c-simple").file))
    val containerId = containerService.startIdeContainer(taskSubmission)
    // assert that file is existing and nothing is owned by root
    val dirContent = containerService.exec(containerId, arrayOf("ls", "-l", "/home/project"))
    assertThat(dirContent, containsString("main.c"))
    assertThat(dirContent, not(containsString("root")))
  }

  private fun listIdeContainer() = docker.listContainers(
      ListContainersParam.withLabel(ContainerService.LABEL_TASK_SUBMISSION_ID)
  )
}
