import PrototypeApp from "../components/PrototypeApp";

export default async function RoutedPrototype({
  params,
}: {
  params: Promise<{ slug: string[] }>;
}) {
  const { slug } = await params;
  return <PrototypeApp initialRoute={`/${slug.join("/")}`} />;
}
