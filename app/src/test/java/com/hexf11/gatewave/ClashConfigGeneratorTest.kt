package com.hexf11.gatewave

import org.junit.Assert.assertTrue
import org.junit.Test

class ClashConfigGeneratorTest {
    @Test
    fun `生成的配置包含端点和 UDP 开关`() {
        val yaml = ClashConfigGenerator.generate(
            serverAddress = "192.168.31.189",
            socksPort = 1080,
            udpEnabled = true,
        )

        assertTrue(yaml.contains("server: 192.168.31.189"))
        assertTrue(yaml.contains("port: 1080"))
        assertTrue(yaml.contains("udp: true"))
    }

    @Test
    fun `局域网直连规则位于兜底规则之前`() {
        val yaml = ClashConfigGenerator.generate("192.168.1.2", 1080, false)

        val lanRule = yaml.indexOf("IP-CIDR,192.168.0.0/16,DIRECT,no-resolve")
        val fallbackRule = yaml.indexOf("MATCH,漏网之鱼")

        assertTrue(lanRule >= 0)
        assertTrue(fallbackRule > lanRule)
    }

    @Test
    fun `配置包含代理分流和国内直连策略`() {
        val yaml = ClashConfigGenerator.generate("192.168.1.2", 1080, true)

        assertTrue(yaml.contains("DOMAIN-SUFFIX,google.com,节点选择"))
        assertTrue(yaml.contains("DOMAIN-SUFFIX,openai.com,节点选择"))
        assertTrue(yaml.contains("DOMAIN-SUFFIX,github.com,节点选择"))
        assertTrue(yaml.contains("DOMAIN-SUFFIX,cn,DIRECT"))
        assertTrue(yaml.contains("GEOIP,CN,DIRECT"))
    }
}
