import type { ThemeConfig } from 'antd'

const kai =
  "'LXGW WenKai Screen', 'Kaiti SC', 'STKaiti', 'KaiTi', serif"
const hei =
  "-apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei UI', 'Microsoft YaHei', sans-serif"

/**
 * 主题只改 token：主色取苔，底色取纸，正文取墨，圆角 4px。
 * 组件库的「模板感」主要来自圆角和阴影，这两项压住，观感就回到账本上。
 */
export const theme: ThemeConfig = {
  token: {
    colorPrimary: '#54663D',
    colorInfo: '#54663D',
    colorLink: '#54663D',
    colorSuccess: '#54663D',
    colorError: '#A4553F',
    colorWarning: '#8A6E24',
    colorBgBase: '#EFF0E9',
    colorBgContainer: '#F7F8F2',
    colorBgElevated: '#F7F8F2',
    colorTextBase: '#1C231F',
    colorText: '#1C231F',
    colorTextSecondary: '#6E7568',
    colorBorder: '#CDD1C3',
    colorBorderSecondary: '#E1E3D9',
    borderRadius: 4,
    borderRadiusLG: 4,
    borderRadiusSM: 2,
    controlHeight: 32,
    fontSize: 13,
    fontFamily: hei,
    fontFamilyCode: "'SFMono-Regular', 'Cascadia Mono', Consolas, monospace",
    boxShadow: 'none',
    boxShadowSecondary: '0 8px 24px rgba(28, 35, 31, 0.08)',
    wireframe: false,
  },
  components: {
    Button: {
      borderRadius: 2,
      controlHeight: 32,
      primaryShadow: 'none',
      defaultShadow: 'none',
      dangerShadow: 'none',
    },
    Input: {
      activeBorderColor: '#54663D',
      hoverBorderColor: '#54663D',
      activeShadow: 'none',
    },
    InputNumber: {
      activeBorderColor: '#54663D',
      hoverBorderColor: '#54663D',
      activeShadow: 'none',
    },
    Select: {
      optionSelectedBg: 'rgba(84, 102, 61, 0.10)',
    },
    Drawer: {
      colorBgElevated: '#F7F8F2',
    },
    Modal: {
      contentBg: '#F7F8F2',
      headerBg: '#F7F8F2',
    },
    Popover: {
      colorBgElevated: '#F7F8F2',
    },
    Tooltip: {
      colorBgSpotlight: '#1C231F',
      colorTextLightSolid: '#F7F8F2',
    },
    Message: {
      contentBg: '#F7F8F2',
    },
    Progress: {
      defaultColor: '#54663D',
      remainingColor: '#CDD1C3',
    },
  },
}

export const fontStacks = { kai, hei }
