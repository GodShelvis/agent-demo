import React, { useEffect, useRef, useState, useCallback } from 'react';
import { Layout, Card, Tag, Button, Typography, Space, Empty, Tooltip } from 'antd';
import {
  PlusOutlined,
  ThunderboltOutlined,
  BookOutlined,
  ClockCircleOutlined,
  DollarOutlined,
} from '@ant-design/icons';
import { Bubble, Sender, ThoughtChain } from '@ant-design/x';

const { Sider, Content } = Layout;
const { Title, Text } = Typography;

const TOOL_LABEL = {
  search_courses: '课程检索',
  query_price: '价格查询',
  match_package: '套餐匹配',
  search_content: '内容检索',
  check_duration: '时长匹配',
  finalize_recommend: '收口推荐',
  create_order: '下单',
};

let msgSeq = 0;
const nextId = () => `m${++msgSeq}`;

function newSessionId() {
  return `s_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}

export default function App() {
  const [courses, setCourses] = useState([]);
  const [selected, setSelected] = useState([]); // 已选课程
  const [messages, setMessages] = useState([]); // 消息流
  const [liveThinking, setLiveThinking] = useState([]); // 本轮实时思考轨迹
  const [loading, setLoading] = useState(false);
  const [sessionId, setSessionId] = useState(newSessionId());

  useEffect(() => {
    fetch('/api/courses')
      .then((r) => r.json())
      .then(setCourses)
      .catch(() => {});
  }, []);

  const addSelected = useCallback((course) => {
    setSelected((prev) => (prev.some((c) => c.id === course.id) ? prev : [...prev, course]));
  }, []);

  const removeSelected = useCallback((id) => {
    setSelected((prev) => prev.filter((c) => c.id !== id));
  }, []);

  // ---------------- 发送并解析 SSE ----------------
  const send = async (text) => {
    const content = (text || '').trim();
    if (!content || loading) return;
    setMessages((prev) => [...prev, { id: nextId(), role: 'user', content }]);
    setLoading(true);
    setLiveThinking([]);

    const body = {
      sessionId,
      userMsg: content,
      selectedData: selected.map((c) => ({
        course_id: c.id,
        name: c.name,
        price: c.price,
        duration_days: c.durationDays,
      })),
    };

    try {
      const resp = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      const reader = resp.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      let answer = '';
      const thinkingSteps = [];

      const parseBlock = (block) => {
        const lines = block.split('\n');
        let event = 'message';
        const datas = [];
        for (const line of lines) {
          if (line.startsWith('event:')) event = line.slice(6).trim();
          else if (line.startsWith('data:')) datas.push(line.slice(5).trim());
        }
        const data = datas.join('\n');
        if (!data) return;

        if (event === 'thinking') {
          thinkingSteps.push({ type: 'thinking', text: data });
          setLiveThinking((prev) => [...prev, { type: 'thinking', text: data }]);
        } else if (event === 'tool_start') {
          const { tool, args } = safeParse(data);
          thinkingSteps.push({ type: 'tool_start', tool, args });
          setLiveThinking((prev) => [...prev, { type: 'tool_start', tool, args }]);
        } else if (event === 'tool_end') {
          const { tool, result } = safeParse(data);
          setLiveThinking((prev) => {
            const copy = [...prev];
            const idx = copy.map((s) => s.tool).lastIndexOf(tool);
            if (idx >= 0) copy[idx] = { ...copy[idx], result };
            return copy;
          });
        } else if (event === 'answer') {
          answer += data;
        }
      };
      const safeParse = (s) => {
        try {
          return JSON.parse(s);
        } catch {
          return { tool: '', args: {}, result: s };
        }
      };

      const flush = () => {
        const blocks = buffer.split('\n\n');
        buffer = blocks.pop() || '';
        for (const block of blocks) parseBlock(block);
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        flush();
      }
      flush();

      setMessages((prev) => [
        ...prev,
        { id: nextId(), role: 'assistant', content: answer || '（无回答）', thinking: [...thinkingSteps] },
      ]);
      setLiveThinking([]);
    } catch (e) {
      setMessages((prev) => [
        ...prev,
        { id: nextId(), role: 'assistant', content: '请求失败：' + e.message },
      ]);
    } finally {
      setLoading(false);
    }
  };

  // ---------------- 拖拽选课 ----------------
  const onDragStart = (e, course) => {
    e.dataTransfer.setData('text/plain', String(course.id));
  };
  const onDrop = (e) => {
    e.preventDefault();
    const id = Number(e.dataTransfer.getData('text/plain'));
    const course = courses.find((c) => c.id === id);
    if (course) addSelected(course);
  };

  // ---------------- 思考轨迹 → ThoughtChain items ----------------
  const thinkingToChain = (steps) =>
    steps.map((s, i) => {
      if (s.type === 'thinking') {
        return { title: `思考${i + 1}`, description: s.text, status: 'success' };
      }
      if (s.type === 'tool_start' || s.tool) {
        const label = TOOL_LABEL[s.tool] || s.tool;
        const desc = (s.result ? '✓ ' + truncate(s.result) : '执行中…') +
          (s.args && Object.keys(s.args).length ? '｜入参 ' + JSON.stringify(s.args) : '');
        return { title: `调用「${label}」`, description: desc, status: s.result ? 'success' : 'pending' };
      }
      return { title: '生成回答', description: '', status: 'success' };
    });

  const truncate = (s) => (s.length > 220 ? s.slice(0, 220) + '…' : s);

  const lastAssistantThinking =
    [...messages].filter((m) => m.role === 'assistant' && m.thinking && m.thinking.length).slice(-1)[0]
      ?.thinking || [];

  return (
    <Layout style={{ height: '100vh' }}>
      {/* 左侧：课程货架 */}
      <Sider width={330} style={{ background: '#fafafa', borderRight: '1px solid #f0f0f0', overflow: 'auto' }}>
        <div style={{ padding: 16 }}>
          <Title level={5} style={{ marginTop: 0 }}>
            📚 课程中心
          </Title>
          <Text type="secondary">点选或拖拽课程到输入区，即可咨询</Text>
        </div>
        <div style={{ padding: '0 12px 16px' }}>
          {courses.map((c) => (
            <Card
              key={c.id}
              size="small"
              draggable
              onDragStart={(e) => onDragStart(e, c)}
              style={{ marginBottom: 10, cursor: 'grab' }}
              styles={{ body: { padding: 12 } }}
              extra={
                <Tooltip title="加入咨询">
                  <Button type="text" size="small" icon={<PlusOutlined />} onClick={() => addSelected(c)} />
                </Tooltip>
              }
            >
              <Text strong>{c.name}</Text>
              <div style={{ marginTop: 6 }}>
                <Space size={4} wrap>
                  <Tag>{c.grade}</Tag>
                  {c.isAfterSchool ? <Tag color="orange">课外</Tag> : <Tag color="blue">课内</Tag>}
                  <Tag icon={<ClockCircleOutlined />}>{c.durationDays}天</Tag>
                  <Tag color="red" icon={<DollarOutlined />}>¥{c.price}</Tag>
                </Space>
              </div>
              <Text type="secondary" style={{ fontSize: 12 }}>
                <BookOutlined /> {c.textbook}
              </Text>
            </Card>
          ))}
        </div>
      </Sider>

      {/* 中间：对话区 */}
      <Content style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <div
          style={{
            padding: '12px 20px',
            borderBottom: '1px solid #f0f0f0',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <Space>
            <ThunderboltOutlined style={{ color: '#1677ff' }} />
            <Title level={5} style={{ margin: 0 }}>语培优选 · 课程智能顾问</Title>
          </Space>
          <Button
            size="small"
            onClick={() => {
              setSessionId(newSessionId());
              setMessages([]);
              setLiveThinking([]);
            }}
          >
            新会话
          </Button>
        </div>

        {/* 已选课程 */}
        {selected.length > 0 && (
          <div style={{ padding: '8px 20px', borderBottom: '1px dashed #d9d9d9', background: '#fffbe6' }}>
            <Text type="secondary" style={{ marginRight: 8 }}>
              已选课程（作为咨询上下文）:
            </Text>
            {selected.map((c) => (
              <Tag key={c.id} closable onClose={() => removeSelected(c.id)} color="gold">
                {c.name} ¥{c.price}
              </Tag>
            ))}
          </div>
        )}

        {/* 消息流 */}
        <div style={{ flex: 1, overflow: 'auto', padding: 20 }}>
          {messages.length === 0 && !loading ? (
            <Empty
              style={{ marginTop: 60 }}
              description={
                <span>
                  你好，我是课程顾问 👋<br />
                  直接提问："我想学英语"、"雅思要多少钱"、"一个月能学完吗"
                  <br />
                  或从左侧选课拖进输入区，我会结合课程为你解答
                </span>
              }
            />
          ) : (
            messages.map((m) => (
              <Bubble
                key={m.id}
                placement={m.role === 'user' ? 'end' : 'start'}
                role={m.role}
                content={m.content}
                style={{ marginBottom: 12, whiteSpace: 'pre-wrap' }}
              />
            ))
          )}
        </div>

        {/* 输入区（可拖入课程） */}
        <div
          onDragOver={(e) => e.preventDefault()}
          onDrop={onDrop}
          style={{ padding: '12px 20px 16px', borderTop: '1px solid #f0f0f0', background: '#fff' }}
        >
          <Sender
            loading={loading}
            placeholder="输入你的问题，或把左侧课程拖到这里…"
            onSubmit={send}
          />
          <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 4 }}>
            提示：可将左侧课程卡片拖拽到此处或点击 + 号，AI 会结合所选课程回答
          </Text>
        </div>
      </Content>

      {/* 右侧：推理过程面板 */}
      <Sider width={340} style={{ background: '#fafafa', borderLeft: '1px solid #f0f0f0', overflow: 'auto' }}>
        <div style={{ padding: 16 }}>
          <Title level={5} style={{ marginTop: 0 }}>🤖 推理过程</Title>
          {liveThinking.length === 0 && lastAssistantThinking.length === 0 && (
            <Text type="secondary">这里会实时展示 AI 的工具调用轨迹</Text>
          )}
          {loading && liveThinking.length === 0 && <Text type="secondary">思考中…</Text>}
          <ThoughtChain items={thinkingToChain(liveThinking)} />
          {liveThinking.length === 0 && lastAssistantThinking.length > 0 && (
            <>
              <Text type="secondary" style={{ fontSize: 12 }}>（上轮轨迹）</Text>
              <ThoughtChain items={thinkingToChain(lastAssistantThinking)} />
            </>
          )}
        </div>
      </Sider>
    </Layout>
  );
}
