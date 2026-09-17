package com.example.runtime.routing;

import com.example.runtime.assignment.InstanceProjectEntity;
import com.example.runtime.assignment.InstanceProjectRepository;
import com.example.runtime.assignment.InstanceTopicPrefixRepository;
import com.example.runtime.assignment.InstanceTopicRepository;
import com.example.runtime.instance.InstanceEntity;
import com.example.runtime.instance.InstanceIdentity;
import com.example.runtime.instance.InstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Запрос по чужому проекту уходит владельцу ровно один раз. Запрос, уже пересланный, повторно не
 * пересылается — иначе два экземпляра с разными представлениями о назначении гоняли бы его по кругу.
 */
class OwnerForwardingFilterTest {

    @Test
    void чужой_проект_пересылается_владельцу_а_пересланный_запрос_нет() throws Exception {
        InstanceProjectRepository projects = mock(InstanceProjectRepository.class);
        InstanceProjectEntity owned = new InstanceProjectEntity();
        owned.setProjectId(9000L);
        owned.setInstanceId("runtime-2");
        when(projects.findById(9000L)).thenReturn(Optional.of(owned));
        InstanceRepository instances = mock(InstanceRepository.class);
        InstanceEntity owner = new InstanceEntity();
        owner.setInstanceId("runtime-2");
        owner.setBaseUrl("http://runtime-2:8085");
        owner.setLastSeenAt(Instant.now());
        when(instances.findById("runtime-2")).thenReturn(Optional.of(owner));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runtime-2:8085/api/runtime/recipes/%D1%82%D0%B0%D0%BD%D0%BA/confirm"))
                .andExpect(header("X-Runtime-Forwarded", "runtime-1"))
                .andRespond(withSuccess("{\"stepIndex\":3}", MediaType.APPLICATION_JSON));

        OwnerForwardingFilter filter = new OwnerForwardingFilter(new InstanceIdentity("runtime-1", "http://runtime-1:8085"),
                instances, projects, mock(InstanceTopicPrefixRepository.class), mock(InstanceTopicRepository.class), builder);

        // id рецепта — кириллический слаг: путь приходит закодированным и не должен кодироваться повторно.
        MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.POST.name(),
                "/api/runtime/recipes/%D1%82%D0%B0%D0%BD%D0%BA/confirm");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("{\"projectId\":9000}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        server.verify();
        assertThat(chain.getRequest()).isNull();   // локально не выполнялся
        assertThat(response.getContentAsString()).contains("\"stepIndex\":3");

        MockHttpServletRequest again = new MockHttpServletRequest(HttpMethod.POST.name(), "/api/runtime/recipes/r1/confirm");
        again.setContentType(MediaType.APPLICATION_JSON_VALUE);
        again.setContent("{\"projectId\":9000}".getBytes(StandardCharsets.UTF_8));
        again.addHeader("X-Runtime-Forwarded", "runtime-3");
        MockHttpServletResponse againResponse = new MockHttpServletResponse();
        filter.doFilter(again, againResponse, new MockFilterChain());

        assertThat(againResponse.getStatus()).isEqualTo(409);
        assertThat(againResponse.getContentAsString()).contains("не назначен этому экземпляру");
    }
}
