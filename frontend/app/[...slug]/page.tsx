import DocGridApp from "../components/DocGridApp";

export default async function RoutedDocGrid({
  params,
}: {
  params: Promise<{ slug: string[] }>;
}) {
  const { slug } = await params;
  return <DocGridApp initialRoute={`/${slug.join("/")}`} />;
}
