package com.example.editor.service.autobind;

import com.example.editor.client.ChannelTree;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelIndexTest {

    private static final String ROOT = "Барановичи-1.Test";

    private static ChannelTree tree() {
        return new ChannelTree(
                List.of(ROOT + ".LINE1", ROOT + ".LINE1.V0", ROOT + ".LINE1.V0.ST", ROOT + ".LINE1.V0.M",
                        ROOT + ".STATION", ROOT + ".STATION.LT1", ROOT + ".STATION.LT1.V"),
                List.of(new ChannelTree.Param(ROOT + ".LINE1.V0.ST", ChannelIndex.PLC_NAME, "LINE1V0.ST"),
                        new ChannelTree.Param(ROOT + ".LINE1.V0.M", ChannelIndex.PLC_NAME, "LINE1V0.M"),
                        new ChannelTree.Param(ROOT + ".LINE1.V0.ST", "Описание", "Клапан V00")));
    }

    @Test
    void объект_находится_по_объектному_пути_и_по_старому_имени() {
        ChannelIndex index = ChannelIndex.build(ROOT, tree());

        assertThat(index.objectOf("LINE1.V0")).contains("LINE1.V0");
        assertThat(index.objectOf(" LINE1V0 ")).contains("LINE1.V0");
        assertThat(index.objectOf("STATION.LT1")).contains("STATION.LT1");
        assertThat(index.objectOf("Переключаемый клапан")).isEmpty();
        assertThat(index.tagOf("LINE1.V0", "ST")).contains(ROOT + ".LINE1.V0.ST");
        assertThat(index.tagOf("LINE1.V0", "P_ON_TIME")).isEmpty();
    }

    /** Одно старое имя у двух объектов — угадывать нельзя, ключ выбрасывается. */
    @Test
    void неоднозначное_старое_имя_не_находится() {
        ChannelTree tree = new ChannelTree(
                List.of(ROOT + ".A.V0", ROOT + ".A.V0.ST", ROOT + ".B.V0", ROOT + ".B.V0.ST"),
                List.of(new ChannelTree.Param(ROOT + ".A.V0.ST", ChannelIndex.PLC_NAME, "LINE1V0.ST"),
                        new ChannelTree.Param(ROOT + ".B.V0.ST", ChannelIndex.PLC_NAME, "LINE1V0.ST")));

        ChannelIndex index = ChannelIndex.build(ROOT, tree);

        assertThat(index.objectOf("LINE1V0")).isEmpty();
        assertThat(index.objectOf("A.V0")).contains("A.V0");
    }
}
