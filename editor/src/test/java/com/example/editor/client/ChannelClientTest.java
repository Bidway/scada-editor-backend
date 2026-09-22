package com.example.editor.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

class ChannelClientTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ChannelClient client = new ChannelClient(builder, "http://channel");

    @Test
    void поддерево_разбирается_в_узлы_и_параметры() {
        server.expect(requestTo(startsWith("http://channel/api/channel/node/fullHierarchy")))
                .andExpect(queryParam("rootPath", "%D0%A2.Test"))
                .andRespond(withSuccess("""
                        {"nodes":[{"key":"Т.Test.LINE1.V0.ST"}],
                         "params":[{"key":1,"parentKey":"Т.Test.LINE1.V0.ST","name":"Имя в ПЛК","type":"input","value":"LINE1V0.ST"}]}
                        """, MediaType.APPLICATION_JSON));

        ChannelTree tree = client.fetchTree("Т.Test");

        assertThat(tree.nodes()).containsExactly("Т.Test.LINE1.V0.ST");
        assertThat(tree.params()).containsExactly(
                new ChannelTree.Param("Т.Test.LINE1.V0.ST", "Имя в ПЛК", "LINE1V0.ST"));
    }

    @Test
    void сбой_channel_это_недоступность_а_не_500() {
        server.expect(requestTo(startsWith("http://channel/"))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.fetchTree("Т.Test")).isInstanceOf(ChannelUnavailableException.class);
    }
}
